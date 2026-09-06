package dev.fordes.adfs.format;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import dev.fordes.adfs.config.ContainerFormat;
import dev.fordes.adfs.config.OutputSpec;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.error.OutputException;
import dev.fordes.adfs.rule.spool.RuleSpool;
import dev.fordes.adfs.rule.conversion.ConversionDecision;
import dev.fordes.adfs.rule.conversion.ConversionPolicy;
import dev.fordes.adfs.rule.conversion.ConversionScope;
import dev.fordes.adfs.rule.conversion.WhitelistMatcher;
import dev.fordes.adfs.rule.dedup.OutputDeduplicator;
import dev.fordes.adfs.rule.model.AdblockNetworkRule;
import dev.fordes.adfs.rule.model.AdblockPattern;
import dev.fordes.adfs.rule.model.AllOf;
import dev.fordes.adfs.rule.model.AnyOf;
import dev.fordes.adfs.rule.model.CosmeticRule;
import dev.fordes.adfs.rule.model.DomainMatch;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainPattern;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.HostMappingRule;
import dev.fordes.adfs.rule.model.IpAddress;
import dev.fordes.adfs.rule.model.IpCidr;
import dev.fordes.adfs.rule.model.IpCidrMatch;
import dev.fordes.adfs.rule.model.IpCidrRule;
import dev.fordes.adfs.rule.model.IpFamily;
import dev.fordes.adfs.rule.model.KeywordDomain;
import dev.fordes.adfs.rule.model.MatchExpression;
import dev.fordes.adfs.rule.model.MatchSide;
import dev.fordes.adfs.rule.model.NetworkMatch;
import dev.fordes.adfs.rule.model.Not;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.PartyConstraint;
import dev.fordes.adfs.rule.model.PortMatch;
import dev.fordes.adfs.rule.model.ProcessMatch;
import dev.fordes.adfs.rule.model.RegexDomain;
import dev.fordes.adfs.rule.model.RouteRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.model.WildcardDomain;

@Slf4j
public final class BasicRuleWriter implements RuleWriter {

    private static final String LF = "\n";
    private final OutputSpec target;
    private final String outputName;
    private final ConversionPolicy policy;
    private final List<DomainName> whitelist;
    private final OutputDeduplicator deduplicator;
    private final OutputStream output;
    private final Map<ConversionDecision, Long> nonExactCounts = new LinkedHashMap<>();
    private boolean finished;

    public BasicRuleWriter(
            OutputSpec target,
            ConversionPolicy policy,
            Set<String> whitelist,
            OutputDeduplicator deduplicator,
            OutputStream output) {
        this.target = target;
        this.outputName = target.path() + " (" + target.type().value()
                + (target.dialect() == RuleDialect.NONE ? "" : "/" + target.dialect().value()) + ")";
        this.policy = policy;
        this.whitelist = whitelist.stream().map(DomainName::new).sorted().toList();
        this.deduplicator = deduplicator;
        this.output = output;
        if (target.container() == ContainerFormat.YAML) {
            writeBytes("payload:" + LF);
        }
    }

    @Override
    public WriteResult write(RuleEntry entry) {
        if (finished) {
            throw new IllegalStateException("Writer finish 后不能继续写入");
        }
        if (entry instanceof OpaqueRule opaque
                && (opaque.type() != target.type() || opaque.dialect() != target.dialect())) {
            if (log.isDebugEnabled()) {
                log.debug("规则未转换:  {} --> {} | {} --> 不透明规则不能跨格式或跨方言转换",
                        MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE));
            }
            return WriteResult.UNSUPPORTED;
        }
        if (shouldRemoveForWhitelist(entry)) {
            return WriteResult.WHITELIST_REMOVED;
        }
        EncodingResult result = encode(entry);
        return switch (result) {
            case Unsupported(String reason) -> {
                if (log.isDebugEnabled()) {
                    log.debug("规则未转换:  {} --> {} | {} --> {}",
                            MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), reason);
                }
                yield WriteResult.UNSUPPORTED;
            }
            case Encoded(String text, ConversionDecision decision, boolean passthrough) -> {
                if (!policy.allows(decision.scope())) {
                    if (decision.scope() != ConversionScope.UNSUPPORTED) {
                        countNonExact(decision);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("规则未转换:  {} --> {} | {} --> 当前策略不允许该转换: {}",
                                MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), decision.reason());
                    }
                    yield WriteResult.UNSUPPORTED;
                }
                if (decision.scope() != ConversionScope.EXACT) {
                    countNonExact(decision);
                }
                byte[] record = text.getBytes(StandardCharsets.UTF_8);
                if (!deduplicator.add(record)) {
                    yield WriteResult.DUPLICATE;
                }
                String outputRule = wrap(text);
                writeBytes(outputRule);
                if (log.isTraceEnabled()) {
                    log.trace("规则转换成功:  {} --> {} | {} --> {}",
                            MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE),
                            outputRule.substring(0, outputRule.length() - LF.length()));
                }
                yield passthrough ? WriteResult.PASSTHROUGH : WriteResult.WRITTEN;
            }
        };
    }

    private EncodingResult encode(RuleEntry entry) {
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) -> encodeDomain(pattern, action);
            case HostMappingRule(IpAddress address, DomainName hostname) -> encodeHost(address, hostname);
            case IpCidrRule(IpAddress network, int prefixLength, RuleAction action) ->
                    encodeIpCidr(network, prefixLength, action);
            case RouteRule(MatchExpression expression) -> encodeRoute(expression);
            case AdblockNetworkRule rule -> encodeAdblockDomain(rule);
            case CosmeticRule _ -> new Unsupported("基础 Writer 不能表达元素规则");
            case OpaqueRule(var type, var dialect, var envelope, String payload) -> {
                if (type == target.type() && dialect == target.dialect()) {
                    yield new Encoded(payload, new ConversionDecision(ConversionScope.EXACT, "同方言透传"), true);
                }
                yield new Unsupported("不透明规则不能跨格式或跨方言转换");
            }
        };
    }

    private EncodingResult encodeAdblockDomain(AdblockNetworkRule rule) {
        if (!isPureDomainRule(rule)) {
            return new Unsupported("目标不能安全表达带约束或复杂主体的 Adblock 规则");
        }
        return encodeDomain(new SuffixDomain(new DomainName(rule.pattern().value())), rule.action());
    }

    private static boolean isPureDomainRule(AdblockNetworkRule rule) {
        return rule.pattern().kind() == AdblockPattern.Kind.DOMAIN_ANCHOR
                && rule.includedResourceTypes().isEmpty() && rule.excludedResourceTypes().isEmpty()
                && rule.domainConstraints().isEmpty() && rule.partyConstraint() == PartyConstraint.ANY
                && !rule.matchCase() && !rule.important() && rule.modifiers().isEmpty();
    }

    private EncodingResult encodeDomain(DomainPattern pattern, RuleAction action) {
        return switch (target.type()) {
            case HOSTS -> encodeHostsDomain(pattern, action);
            case DNS -> encodeDnsDomain(pattern, action);
            case DNSMASQ -> encodeDnsmasqDomain(pattern, action);
            case MIHOMO -> encodeMihomoDomain(pattern, action);
            case ADBLOCK, SING_BOX, SMARTDNS -> new Unsupported("目标格式尚未在基础 Writer 中实现");
        };
    }

    private static EncodingResult encodeHostsDomain(DomainPattern pattern, RuleAction action) {
        if (action == RuleAction.ALLOW) {
            return new Unsupported("Hosts 不能表达允许规则");
        }
        ConversionScope scope = pattern instanceof ExactDomain ? ConversionScope.EXACT : ConversionScope.REDUCED;
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return new Unsupported("Hosts 不能表达关键词、通配符或正则域名");
        }
        return new Encoded("0.0.0.0 " + pattern.value(),
                new ConversionDecision(scope, "Hosts 只能写出单一主机名"), false);
    }

    private static EncodingResult encodeDnsDomain(DomainPattern pattern, RuleAction action) {
        String prefix = action == RuleAction.ALLOW ? "@@" : "";
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return new Unsupported("DNS 基础 Writer 尚不能表达复杂域名模式");
        }
        String text = pattern instanceof ExactDomain ? "|" + pattern.value() + "|" : "||" + pattern.value() + "^";
        return new Encoded(prefix + text,
                new ConversionDecision(ConversionScope.EXACT, "DNS 域名边界精确转换"), false);
    }

    private static EncodingResult encodeDnsmasqDomain(DomainPattern pattern, RuleAction action) {
        if (action == RuleAction.ALLOW) {
            return new Unsupported("Dnsmasq 规则集不能表达允许规则");
        }
        ConversionScope scope = pattern instanceof SuffixDomain ? ConversionScope.EXACT : ConversionScope.EXPANDED;
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return new Unsupported("Dnsmasq 不能安全表达复杂域名模式");
        }
        return new Encoded("address=/" + pattern.value() + "/#",
                new ConversionDecision(scope, "Dnsmasq 域名段覆盖根域名及子域"), false);
    }

    private EncodingResult encodeMihomoDomain(DomainPattern pattern, RuleAction action) {
        if (action == RuleAction.ALLOW) {
            return new Unsupported("Mihomo 中性规则集不能表达允许动作");
        }
        if (target.dialect() == RuleDialect.IPCIDR) {
            return new Unsupported("Mihomo ipcidr 不能表达域名规则");
        }
        if (target.dialect() == RuleDialect.CLASSICAL) {
            String type = switch (pattern) {
                case ExactDomain _ -> "DOMAIN";
                case SuffixDomain _ -> "DOMAIN-SUFFIX";
                case KeywordDomain _ -> "DOMAIN-KEYWORD";
                case WildcardDomain _ -> "DOMAIN-WILDCARD";
                case RegexDomain _ -> "";
            };
            if (type.isEmpty()) {
                return new Unsupported("Mihomo classical 不能表达域名正则");
            }
            return new Encoded(type + "," + csvField(pattern.value()),
                    new ConversionDecision(ConversionScope.EXACT, "Mihomo classical 域名规则精确转换"), false);
        }
        String prefix = pattern instanceof SuffixDomain ? "." : "";
        if (pattern instanceof RegexDomain || pattern instanceof KeywordDomain) {
            return new Unsupported("Mihomo domain 不支持关键词或正则域名");
        }
        return new Encoded(prefix + pattern.value(),
                new ConversionDecision(ConversionScope.EXACT, "Mihomo domain 可精确表达域名模式"), false);
    }

    private EncodingResult encodeHost(IpAddress address, DomainName hostname) {
        return switch (target.type()) {
            case HOSTS, DNS -> new Encoded(address.text() + " " + hostname.value(),
                    new ConversionDecision(ConversionScope.EXACT, "目标支持 hosts 映射"), false);
            case DNSMASQ -> new Encoded("address=/" + hostname.value() + "/" + address.text(),
                    new ConversionDecision(ConversionScope.EXPANDED, "Dnsmasq address 同时覆盖子域"), false);
            case MIHOMO, ADBLOCK, SING_BOX, SMARTDNS -> new Unsupported("目标不能表达地址映射");
        };
    }

    private EncodingResult encodeIpCidr(IpAddress network, int prefixLength, RuleAction action) {
        if (target.type() != RuleType.MIHOMO || target.dialect() == RuleDialect.DOMAIN
                || action == RuleAction.ALLOW) {
            return new Unsupported("目标不能表达 IP/CIDR 阻断集合");
        }
        String cidr = new IpCidr(network, prefixLength).text();
        String text = target.dialect() == RuleDialect.CLASSICAL
                ? (network.family() == IpFamily.IPV4 ? "IP-CIDR," : "IP-CIDR6,") + cidr
                : cidr;
        return new Encoded(text,
                new ConversionDecision(ConversionScope.EXACT, "Mihomo ipcidr 精确转换"), false);
    }

    private EncodingResult encodeRoute(MatchExpression expression) {
        if (target.type() != RuleType.MIHOMO || target.dialect() != RuleDialect.CLASSICAL) {
            return new Unsupported("目标不能表达复合路由规则");
        }
        String text = switch (expression) {
            case DomainMatch(DomainPattern pattern) -> switch (encodeMihomoDomain(pattern, RuleAction.BLOCK)) {
                case Encoded(String encoded, var decision, var passthrough) -> encoded;
                case Unsupported _ -> "";
            };
            case PortMatch(MatchSide side, int first, int last) ->
                    (side == MatchSide.DESTINATION ? "DST-PORT," : "SRC-PORT,")
                            + (first == last ? first : first + "-" + last);
            case NetworkMatch(var network) -> "NETWORK," + network.value();
            case ProcessMatch(var field, String value) -> switch (field) {
                case NAME -> "PROCESS-NAME," + csvField(value);
                case PATH -> "PROCESS-PATH," + csvField(value);
                case PACKAGE -> "";
            };
            case IpCidrMatch(MatchSide side, IpAddress network, int prefixLength) ->
                    (side == MatchSide.SOURCE ? "SRC-IP-CIDR," :
                            network.family() == IpFamily.IPV4 ? "IP-CIDR," : "IP-CIDR6,")
                            + new IpCidr(network, prefixLength).text();
            case AllOf _, AnyOf _, Not _ -> "";
        };
        if (text.isEmpty()) {
            return new Unsupported("Mihomo classical 尚不能表达该复合规则");
        }
        return new Encoded(text, new ConversionDecision(ConversionScope.EXACT, "Mihomo classical 路由规则精确转换"),
                false);
    }

    private static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private boolean shouldRemoveForWhitelist(RuleEntry entry) {
        if (whitelist.isEmpty()) {
            return false;
        }
        if (target.type() == RuleType.DNS) {
            return entry instanceof OpaqueRule;
        }
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) ->
                    action == RuleAction.BLOCK && whitelist.stream().anyMatch(domain -> intersects(pattern, domain));
            case HostMappingRule(var address, DomainName hostname) ->
                    whitelist.stream().anyMatch(domain -> isWithin(hostname, domain));
            case IpCidrRule _ -> false;
            case AdblockNetworkRule rule -> rule.action() == RuleAction.BLOCK && (!isPureDomainRule(rule)
                    || whitelist.stream().anyMatch(domain -> isWithin(
                            new DomainName(rule.pattern().value()), domain)
                            || isWithin(domain, new DomainName(rule.pattern().value()))));
            case RouteRule(var expression) ->
                    whitelist.stream().anyMatch(domain -> WhitelistMatcher.intersects(expression, domain));
            case CosmeticRule _ -> true;
            case OpaqueRule _ -> true;
        };
    }

    private static boolean intersects(DomainPattern pattern, DomainName whitelist) {
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return true;
        }
        DomainName domain = pattern instanceof ExactDomain exact ? exact.domain() : ((SuffixDomain) pattern).domain();
        if (pattern instanceof ExactDomain) {
            return isWithin(domain, whitelist);
        }
        return isWithin(domain, whitelist) || isWithin(whitelist, domain);
    }

    private static boolean isWithin(DomainName candidate, DomainName parent) {
        return candidate.equals(parent) || candidate.value().endsWith("." + parent.value());
    }

    private String wrap(String text) {
        if (target.container() == ContainerFormat.YAML) {
            return "  - '" + text.replace("'", "''") + "'" + LF;
        }
        return text + LF;
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        if (target.type() == RuleType.DNS) {
            for (DomainName domain : whitelist) {
                String exception = "@@||" + domain.value() + "^";
                byte[] record = exception.getBytes(StandardCharsets.UTF_8);
                if (deduplicator.add(record)) {
                    writeBytes(wrap(exception));
                }
            }
        }
        nonExactCounts.forEach((decision, count) -> log.warn(
                "目标「{}」有 {} 条规则{}，转换范围为 {}: {}",
                target.path(), count, policy.allows(decision.scope()) ? "采用非精确转换" : "被转换策略拒绝",
                decision.scope().value(), decision.reason()));
        try {
            output.flush();
            finished = true;
        } catch (IOException exception) {
            throw new OutputException("刷新目标输出失败: path=" + target.path(), exception);
        }
    }

    private void countNonExact(ConversionDecision decision) {
        nonExactCounts.merge(decision, 1L, Long::sum);
    }

    private void writeBytes(String value) {
        try {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new OutputException("写入目标输出失败: path=" + target.path(), exception);
        }
    }

    @Override
    public void close() {
        try (output) {
            finish();
        } catch (IOException exception) {
            throw new OutputException("关闭目标输出失败: path=" + target.path(), exception);
        }
    }
}

sealed interface EncodingResult permits Encoded, Unsupported {
}

record Encoded(String text, ConversionDecision decision, boolean passthrough) implements EncodingResult {
}

record Unsupported(String reason) implements EncodingResult {
}

package dev.fordes.adfs.format.singbox;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import dev.fordes.adfs.config.OutputSpec;
import dev.fordes.adfs.error.OutputException;
import dev.fordes.adfs.format.RuleWriter;
import dev.fordes.adfs.format.WriteResult;
import dev.fordes.adfs.rule.spool.RuleSpool;
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
import dev.fordes.adfs.rule.model.IpCidr;
import dev.fordes.adfs.rule.model.IpCidrMatch;
import dev.fordes.adfs.rule.model.IpCidrRule;
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
public final class SingBoxWriter implements RuleWriter {

    private static final int FORMAT_VERSION = 3;
    private final OutputSpec target;
    private final String outputName;
    private final List<DomainName> whitelist;
    private final OutputDeduplicator deduplicator;
    private final JsonFactory factory = new JsonFactory();
    private final JsonGenerator output;
    private boolean finished;

    public SingBoxWriter(OutputSpec target, Set<String> whitelist, OutputDeduplicator deduplicator, OutputStream stream) {
        this.target = target;
        this.outputName = target.path() + " (" + target.type().value() + ")";
        this.whitelist = whitelist.stream().map(DomainName::new).sorted().toList();
        this.deduplicator = deduplicator;
        try {
            output = factory.createGenerator(ObjectWriteContext.empty(), stream);
            output.writeStartObject();
            output.writeNumberProperty("version", FORMAT_VERSION);
            output.writeArrayPropertyStart("rules");
        } catch (JacksonException exception) {
            throw new OutputException("初始化 Sing-box 输出失败: path=" + target.path(), exception);
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
        if (shouldRemove(entry)) {
            return WriteResult.WHITELIST_REMOVED;
        }
        Optional<SingEncoding> encoded = encode(entry);
        if (encoded.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("规则未转换:  {} --> {} | {} --> Sing-box 无法表达该规则类型、匹配条件或允许动作",
                        MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE));
            }
            return WriteResult.UNSUPPORTED;
        }
        SingEncoding value = encoded.orElseThrow();
        if (!deduplicator.add(value.bytes())) {
            return WriteResult.DUPLICATE;
        }
        try {
            String text = new String(value.bytes(), StandardCharsets.UTF_8);
            output.writeRawValue(text);
            if (log.isTraceEnabled()) {
                log.trace("规则转换成功:  {} --> {} | {} --> {}",
                        MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), text);
            }
            return value.passthrough() ? WriteResult.PASSTHROUGH : WriteResult.WRITTEN;
        } catch (JacksonException exception) {
            throw new OutputException("写入 Sing-box 规则失败: path=" + target.path(), exception);
        }
    }

    private Optional<SingEncoding> encode(RuleEntry entry) {
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) -> action == RuleAction.ALLOW
                    || pattern instanceof WildcardDomain
                    ? Optional.empty() : Optional.of(encodeExpression(new DomainMatch(pattern), false));
            case IpCidrRule(var network, int prefixLength, RuleAction action) -> action == RuleAction.ALLOW
                    ? Optional.empty() : Optional.of(encodeExpression(
                            new IpCidrMatch(MatchSide.DESTINATION, network, prefixLength), false));
            case RouteRule(MatchExpression expression) -> supports(expression)
                    ? Optional.of(encodeExpression(expression, false)) : Optional.empty();
            case AdblockNetworkRule rule -> isPureDomainRule(rule) && rule.action() == RuleAction.BLOCK
                    ? Optional.of(encodeExpression(new DomainMatch(
                            new SuffixDomain(new DomainName(rule.pattern().value()))), false)) : Optional.empty();
            case CosmeticRule _ -> Optional.empty();
            case HostMappingRule _ -> Optional.empty();
            case OpaqueRule opaque -> opaque.type() == target.type() && opaque.dialect() == target.dialect()
                    ? Optional.of(new SingEncoding(opaque.payload().getBytes(StandardCharsets.UTF_8), true))
                    : Optional.empty();
        };
    }

    private static boolean isPureDomainRule(AdblockNetworkRule rule) {
        return rule.pattern().kind() == AdblockPattern.Kind.DOMAIN_ANCHOR
                && rule.includedResourceTypes().isEmpty() && rule.excludedResourceTypes().isEmpty()
                && rule.domainConstraints().isEmpty()
                && rule.partyConstraint() == PartyConstraint.ANY
                && !rule.matchCase() && !rule.important() && rule.modifiers().isEmpty();
    }

    private static boolean supports(MatchExpression expression) {
        return switch (expression) {
            case DomainMatch(DomainPattern pattern) -> !(pattern instanceof WildcardDomain);
            case IpCidrMatch _, PortMatch _, NetworkMatch _, ProcessMatch _ -> true;
            case AllOf(List<MatchExpression> expressions) -> expressions.stream().allMatch(SingBoxWriter::supports);
            case AnyOf(List<MatchExpression> expressions) -> expressions.stream().allMatch(SingBoxWriter::supports);
            case Not(MatchExpression child) -> supports(child);
        };
    }

    private SingEncoding encodeExpression(MatchExpression expression, boolean passthrough) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), bytes)) {
            writeExpression(generator, expression, false);
            generator.flush();
            return new SingEncoding(bytes.toByteArray(), passthrough);
        } catch (JacksonException exception) {
            throw new OutputException("编码 Sing-box 规则失败: path=" + target.path(), exception);
        }
    }

    private static void writeExpression(JsonGenerator generator, MatchExpression expression, boolean invert)
            throws JacksonException {
        if (expression instanceof Not(MatchExpression child)) {
            writeExpression(generator, child, !invert);
            return;
        }
        generator.writeStartObject();
        switch (expression) {
            case DomainMatch(DomainPattern pattern) -> writeDomain(generator, pattern);
            case IpCidrMatch(MatchSide side, var network, int prefixLength) ->
                    generator.writeArrayPropertyStart(side == MatchSide.SOURCE ? "source_ip_cidr" : "ip_cidr").writeString(
                            new IpCidr(network, prefixLength).text()).writeEndArray();
            case PortMatch(MatchSide side, int first, int last) -> {
                if (first == last) {
                    generator.writeArrayPropertyStart(side == MatchSide.SOURCE ? "source_port" : "port")
                            .writeNumber(first).writeEndArray();
                } else {
                    generator.writeArrayPropertyStart(side == MatchSide.SOURCE ? "source_port_range" : "port_range")
                            .writeString(first + ":" + last).writeEndArray();
                }
            }
            case NetworkMatch(var network) ->
                    generator.writeArrayPropertyStart("network").writeString(network.value()).writeEndArray();
            case ProcessMatch(var field, String value) -> {
                String name = switch (field) {
                    case NAME -> "process_name";
                    case PATH -> "process_path";
                    case PACKAGE -> "package_name";
                };
                generator.writeArrayPropertyStart(name).writeString(value).writeEndArray();
            }
            case AllOf(List<MatchExpression> expressions) -> writeLogical(generator, "and", expressions);
            case AnyOf(List<MatchExpression> expressions) -> writeLogical(generator, "or", expressions);
            case Not _ -> throw new IllegalStateException("Not 已在对象开始前处理");
        }
        if (invert) {
            generator.writeBooleanProperty("invert", true);
        }
        generator.writeEndObject();
    }

    private static void writeDomain(JsonGenerator generator, DomainPattern pattern) throws JacksonException {
        String field = switch (pattern) {
            case ExactDomain _ -> "domain";
            case SuffixDomain _ -> "domain_suffix";
            case KeywordDomain _ -> "domain_keyword";
            case RegexDomain _ -> "domain_regex";
            case WildcardDomain _ -> "";
        };
        if (field.isEmpty()) {
            throw new OutputException("Sing-box 不能表达 Mihomo 通配域名");
        }
        generator.writeArrayPropertyStart(field).writeString(pattern.value()).writeEndArray();
    }

    private static void writeLogical(JsonGenerator generator, String mode, List<MatchExpression> expressions)
            throws JacksonException {
        generator.writeStringProperty("type", "logical");
        generator.writeStringProperty("mode", mode);
        generator.writeArrayPropertyStart("rules");
        for (MatchExpression expression : expressions) {
            writeExpression(generator, expression, false);
        }
        generator.writeEndArray();
    }

    private boolean shouldRemove(RuleEntry entry) {
        if (whitelist.isEmpty()) {
            return false;
        }
        return switch (entry) {
            case IpCidrRule _ -> false;
            case DomainRule(var pattern, RuleAction action) -> action == RuleAction.BLOCK && intersects(pattern);
            case HostMappingRule(var address, DomainName hostname) -> whitelist.stream().anyMatch(
                    allowed -> within(hostname.value(), allowed.value()));
            case AdblockNetworkRule rule -> rule.action() == RuleAction.BLOCK && (!isPureDomainRule(rule)
                    || whitelist.stream().anyMatch(allowed -> within(rule.pattern().value(), allowed.value())
                            || within(allowed.value(), rule.pattern().value())));
            case RouteRule(var expression) ->
                    whitelist.stream().anyMatch(allowed -> WhitelistMatcher.intersects(expression, allowed));
            case CosmeticRule _, OpaqueRule _ -> true;
        };
    }

    private boolean intersects(DomainPattern pattern) {
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return true;
        }
        String domain = pattern.value();
        return whitelist.stream().anyMatch(allowed -> pattern instanceof ExactDomain
                ? within(domain, allowed.value())
                : within(domain, allowed.value()) || within(allowed.value(), domain));
    }

    private static boolean within(String candidate, String parent) {
        return candidate.equals(parent) || candidate.endsWith("." + parent);
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        try {
            output.writeEndArray();
            output.writeEndObject();
            output.flush();
            finished = true;
        } catch (JacksonException exception) {
            throw new OutputException("结束 Sing-box 输出失败: path=" + target.path(), exception);
        }
    }

    @Override
    public void close() {
        try (output) {
            finish();
        } catch (JacksonException exception) {
            throw new OutputException("关闭 Sing-box 输出失败: path=" + target.path(), exception);
        }
    }
}

record SingEncoding(byte[] bytes, boolean passthrough) {

    SingEncoding {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

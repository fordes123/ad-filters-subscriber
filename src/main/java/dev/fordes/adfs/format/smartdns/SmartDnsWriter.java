package dev.fordes.adfs.format.smartdns;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import dev.fordes.adfs.config.OutputSpec;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.error.OutputException;
import dev.fordes.adfs.format.RuleWriter;
import dev.fordes.adfs.format.WriteResult;
import dev.fordes.adfs.rule.spool.RuleSpool;
import dev.fordes.adfs.rule.conversion.WhitelistMatcher;
import dev.fordes.adfs.rule.dedup.OutputDeduplicator;
import dev.fordes.adfs.rule.model.AdblockNetworkRule;
import dev.fordes.adfs.rule.model.AdblockPattern;
import dev.fordes.adfs.rule.model.CosmeticRule;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainPattern;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.HostMappingRule;
import dev.fordes.adfs.rule.model.IpCidrRule;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.PartyConstraint;
import dev.fordes.adfs.rule.model.RouteRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.model.WildcardDomain;

@Slf4j
public final class SmartDnsWriter implements RuleWriter {

    private static final String LF = "\n";
    private final OutputSpec target;
    private final String outputName;
    private final List<DomainName> whitelist;
    private final OutputDeduplicator deduplicator;
    private final OutputStream output;
    private boolean finished;

    public SmartDnsWriter(OutputSpec target, Set<String> whitelist, OutputDeduplicator deduplicator, OutputStream output) {
        this.target = target;
        this.outputName = target.path() + " (" + target.type().value() + ")";
        this.whitelist = whitelist.stream().map(DomainName::new).sorted().toList();
        this.deduplicator = deduplicator;
        this.output = output;
    }

    @Override
    public WriteResult write(RuleEntry entry) {
        if (finished) {
            throw new IllegalStateException("Writer finish 后不能继续写入");
        }
        Optional<Encoding> encoded = encode(entry);
        if (encoded.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("规则未转换:  {} --> {} | {} --> SmartDNS 无法表达该规则类型、域名模式或约束",
                        MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE));
            }
            return WriteResult.UNSUPPORTED;
        }
        if (shouldRemoveForWhitelist(entry)) {
            return WriteResult.WHITELIST_REMOVED;
        }
        Encoding value = encoded.orElseThrow();
        byte[] bytes = value.text().getBytes(StandardCharsets.UTF_8);
        if (!deduplicator.add(bytes)) {
            return WriteResult.DUPLICATE;
        }
        writeBytes(value.text() + LF);
        if (log.isTraceEnabled()) {
            log.trace("规则转换成功:  {} --> {} | {} --> {}",
                    MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), value.text());
        }
        return value.passthrough() ? WriteResult.PASSTHROUGH : WriteResult.WRITTEN;
    }

    private Optional<Encoding> encode(RuleEntry entry) {
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) -> encodeDomain(pattern, action);
            case HostMappingRule(var address, DomainName hostname) -> Optional.of(
                    new Encoding("address /-." + hostname.value() + "/" + address.text(), false));
            case AdblockNetworkRule rule -> isPureDomainRule(rule)
                    ? encodeDomain(new SuffixDomain(new DomainName(rule.pattern().value())), rule.action())
                    : Optional.empty();
            case OpaqueRule opaque -> opaque.type() == RuleType.SMARTDNS && opaque.dialect() == RuleDialect.NONE
                    ? Optional.of(new Encoding(opaque.payload(), true)) : Optional.empty();
            case CosmeticRule _, IpCidrRule _, RouteRule _ -> Optional.empty();
        };
    }

    private boolean shouldRemoveForWhitelist(RuleEntry entry) {
        if (whitelist.isEmpty()) {
            return false;
        }
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) -> action == RuleAction.BLOCK
                    && whitelist.stream().anyMatch(domain -> WhitelistMatcher.intersects(pattern, domain));
            case HostMappingRule(var address, DomainName hostname) -> whitelist.stream()
                    .anyMatch(domain -> WhitelistMatcher.isWithin(hostname, domain));
            case AdblockNetworkRule rule -> rule.action() == RuleAction.BLOCK && whitelist.stream()
                    .anyMatch(domain -> WhitelistMatcher.intersects(
                            new SuffixDomain(new DomainName(rule.pattern().value())), domain));
            case OpaqueRule _ -> true;
            default -> false;
        };
    }

    private static boolean isPureDomainRule(AdblockNetworkRule rule) {
        return rule.pattern().kind() == AdblockPattern.Kind.DOMAIN_ANCHOR
                && rule.includedResourceTypes().isEmpty() && rule.excludedResourceTypes().isEmpty()
                && rule.domainConstraints().isEmpty()
                && rule.partyConstraint() == PartyConstraint.ANY
                && !rule.matchCase() && !rule.important() && rule.modifiers().isEmpty();
    }

    private static Optional<Encoding> encodeDomain(DomainPattern pattern, RuleAction action) {
        String domain = switch (pattern) {
            case ExactDomain _ -> "-." + pattern.value();
            case SuffixDomain _ -> pattern.value();
            case WildcardDomain wildcard -> wildcard.value();
            default -> "";
        };
        if (domain.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Encoding("address /" + domain + "/"
                + (action == RuleAction.BLOCK ? "#" : "-"), false));
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        for (DomainName domain : whitelist) {
            String rule = "address /" + domain.value() + "/-";
            byte[] bytes = rule.getBytes(StandardCharsets.UTF_8);
            if (deduplicator.add(bytes)) {
                writeBytes(rule + LF);
            }
        }
        try {
            output.flush();
            finished = true;
        } catch (IOException exception) {
            throw new OutputException("刷新 SmartDNS 输出失败: path=" + target.path(), exception);
        }
    }

    private void writeBytes(String value) {
        try {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new OutputException("写入 SmartDNS 输出失败: path=" + target.path(), exception);
        }
    }

    @Override
    public void close() {
        try (output) {
            finish();
        } catch (IOException exception) {
            throw new OutputException("关闭 SmartDNS 输出失败: path=" + target.path(), exception);
        }
    }

    private record Encoding(String text, boolean passthrough) {
    }
}

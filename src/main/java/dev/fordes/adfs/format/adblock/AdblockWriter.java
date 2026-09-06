package dev.fordes.adfs.format.adblock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import dev.fordes.adfs.rule.conversion.ConversionPolicy;
import dev.fordes.adfs.rule.conversion.ConversionScope;
import dev.fordes.adfs.rule.dedup.OutputDeduplicator;
import dev.fordes.adfs.rule.model.AdblockModifier;
import dev.fordes.adfs.rule.model.AdblockNetworkRule;
import dev.fordes.adfs.rule.model.CosmeticRule;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainPattern;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.HostMappingRule;
import dev.fordes.adfs.rule.model.IpCidrRule;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RouteRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.SuffixDomain;

@Slf4j
public final class AdblockWriter implements RuleWriter {

    private static final String LF = "\n";
    private final OutputSpec target;
    private final String outputName;
    private final ConversionPolicy policy;
    private final List<DomainName> whitelist;
    private final OutputDeduplicator deduplicator;
    private final OutputStream output;
    private final AdblockDialectDefinition dialect;
    private long expandedRules;
    private boolean finished;

    public AdblockWriter(
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
        this.dialect = AdblockDialectDefinition.forDialect(target.dialect());
    }

    @Override
    public WriteResult write(RuleEntry entry) {
        if (finished) {
            throw new IllegalStateException("Writer finish 后不能继续写入");
        }
        Optional<Encoding> encoded = encode(entry);
        if (encoded.isEmpty()) {
            if (log.isDebugEnabled()) {
                String reason = entry instanceof DomainRule(ExactDomain _, var _)
                        ? "Adblock 域名规则会覆盖子域名，当前策略禁止扩大匹配范围"
                        : "目标方言不能表达该规则类型或选项";
                log.debug("规则未转换:  {} --> {} | {} --> {}",
                        MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), reason);
            }
            return WriteResult.UNSUPPORTED;
        }
        if (!whitelist.isEmpty() && (entry instanceof OpaqueRule || entry instanceof CosmeticRule
                || entry instanceof AdblockNetworkRule rule && rule.important() && rule.action() == RuleAction.BLOCK)) {
            return WriteResult.WHITELIST_REMOVED;
        }
        Encoding value = encoded.orElseThrow();
        byte[] bytes = value.text().getBytes(StandardCharsets.UTF_8);
        if (!deduplicator.add(bytes)) {
            return WriteResult.DUPLICATE;
        }
        writeBytes(value.text() + LF);
        if (entry instanceof DomainRule(ExactDomain _, var _)) {
            expandedRules++;
        }
        if (log.isTraceEnabled()) {
            log.trace("规则转换成功:  {} --> {} | {} --> {}",
                    MDC.get(RuleSpool.INPUT), outputName, MDC.get(RuleSpool.INPUT_RULE), value.text());
        }
        return value.passthrough() ? WriteResult.PASSTHROUGH : WriteResult.WRITTEN;
    }

    private Optional<Encoding> encode(RuleEntry entry) {
        return switch (entry) {
            case DomainRule(DomainPattern pattern, RuleAction action) -> encodeDomain(pattern, action);
            case AdblockNetworkRule rule -> encodeNetwork(rule);
            case CosmeticRule rule -> dialect.cosmetic(rule.operator().value()) == AdblockCapability.SEMANTIC
                    ? Optional.of(new Encoding(encodeCosmetic(rule), false)) : Optional.empty();
            case OpaqueRule opaque -> opaque.type() == RuleType.ADBLOCK && opaque.dialect() == target.dialect()
                    ? Optional.of(new Encoding(opaque.payload(), true)) : Optional.empty();
            case HostMappingRule _, IpCidrRule _, RouteRule _ -> Optional.empty();
        };
    }

    private Optional<Encoding> encodeDomain(DomainPattern pattern, RuleAction action) {
        if (!(pattern instanceof ExactDomain || pattern instanceof SuffixDomain)) {
            return Optional.empty();
        }
        ConversionScope scope = pattern instanceof ExactDomain ? ConversionScope.EXPANDED : ConversionScope.EXACT;
        if (!policy.allows(scope)) {
            return Optional.empty();
        }
        return Optional.of(new Encoding((action == RuleAction.ALLOW ? "@@" : "")
                + "||" + pattern.value() + "^", false));
    }

    private Optional<Encoding> encodeNetwork(AdblockNetworkRule rule) {
        if (rule.isBadfilter()) {
            return Optional.empty();
        }
        for (AdblockModifier modifier : rule.modifiers()) {
            if (dialect.option(modifier.type().value()) != AdblockCapability.SEMANTIC) {
                return Optional.empty();
            }
        }
        StringBuilder text = new StringBuilder();
        if (rule.action() == RuleAction.ALLOW) {
            text.append("@@");
        }
        text.append(switch (rule.pattern().kind()) {
            case DOMAIN_ANCHOR -> "||" + rule.pattern().value() + "^";
            case URL -> rule.pattern().value();
            case REGEX -> "/" + rule.pattern().value() + "/";
        });
        List<String> options = new ArrayList<>();
        rule.includedResourceTypes().stream().sorted().forEach(type -> options.add(type.value()));
        rule.excludedResourceTypes().stream().sorted().forEach(type -> options.add("~" + type.value()));
        if (!rule.domainConstraints().isEmpty()) {
            options.add("domain=" + String.join("|", rule.domainConstraints().stream()
                    .map(domain -> (domain.excluded() ? "~" : "") + domain.domain().value()).toList()));
        }
        switch (rule.partyConstraint()) {
            case FIRST_PARTY -> options.add("~third-party");
            case THIRD_PARTY -> options.add("third-party");
            case ANY -> {
            }
        }
        if (rule.matchCase()) {
            options.add("match-case");
        }
        if (rule.important()) {
            options.add("important");
        }
        rule.modifiers().forEach(modifier -> options.add(modifier.type().value()
                + (modifier.value().isEmpty() ? "" : "=" + modifier.value())));
        if (!options.isEmpty()) {
            text.append('$').append(String.join(",", options));
        }
        return Optional.of(new Encoding(text.toString(), false));
    }

    private static String encodeCosmetic(CosmeticRule rule) {
        String domains = String.join(",", rule.domains().stream()
                .map(domain -> (domain.excluded() ? "~" : "") + domain.domain().value()).toList());
        return domains + rule.operator().value() + rule.body();
    }

    @Override
    public void finish() {
        if (finished) {
            return;
        }
        for (DomainName domain : whitelist) {
            String exception = "@@||" + domain.value() + "^";
            byte[] bytes = exception.getBytes(StandardCharsets.UTF_8);
            if (deduplicator.add(bytes)) {
                writeBytes(exception + LF);
            }
        }
        try {
            output.flush();
            finished = true;
            if (expandedRules > 0) {
                log.warn("目标「{}」有 {} 条规则扩大匹配范围，转换后的域名规则同时覆盖子域名",
                        target.path(), expandedRules);
            }
        } catch (IOException exception) {
            throw new OutputException("刷新 Adblock 输出失败: path=" + target.path(), exception);
        }
    }

    private void writeBytes(String value) {
        try {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new OutputException("写入 Adblock 输出失败: path=" + target.path(), exception);
        }
    }

    @Override
    public void close() {
        try (output) {
            finish();
        } catch (IOException exception) {
            throw new OutputException("关闭 Adblock 输出失败: path=" + target.path(), exception);
        }
    }

    private record Encoding(String text, boolean passthrough) {
    }
}

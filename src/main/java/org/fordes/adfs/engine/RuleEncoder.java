package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.json.Json;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleBody;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.util.Objects;
import java.util.Optional;

final class RuleEncoder {

    private final AdblockNetworkTranscoder adblockNetworkTranscoder =
            new AdblockNetworkTranscoder();
    private final AdblockExtendedTranscoder adblockExtendedTranscoder =
            new AdblockExtendedTranscoder();

    ConversionResult encode(
            RuleRecord record,
            BuildPlan.OutputSpec output,
            boolean allowNarrowing,
            boolean allowBroadening
    ) {
        Objects.requireNonNull(record, "record 不能为空");
        Objects.requireNonNull(output, "output 不能为空");

        Optional<String> passthrough = passthrough(record, output);
        if (passthrough.isPresent()) {
            return ConversionResult.success(
                    passthrough.orElseThrow()
            );
        }
        return switch (record.body()) {
            case RuleBody.AdblockNetwork network -> encodeNetwork(
                    record,
                    network,
                    output,
                    allowNarrowing,
                    allowBroadening
            );
            case RuleBody.Extended extended -> adblockExtendedTranscoder.transcode(
                    record.raw(),
                    record.sourceDialect(),
                    extended.value(),
                    output
            );
            case RuleBody.Canonical canonical -> applyPolicy(
                    encodeCanonical(canonical.value(), output),
                    allowNarrowing,
                    allowBroadening
            );
            case RuleBody.Opaque ignored -> ConversionResult.unsupported(
                    unsupportedSourceSyntax(record.sourceSyntax(), output)
            );
        };
    }

    private ConversionResult encodeNetwork(
            RuleRecord record,
            RuleBody.AdblockNetwork network,
            BuildPlan.OutputSpec output,
            boolean allowNarrowing,
            boolean allowBroadening
    ) {
        if (record.sourceFormat() == RuleFormat.EASYLIST
                && output.format() == RuleFormat.EASYLIST
                && record.sourceDialect() != DialectProfile.ADBLOCK_BASE
                && output.dialect() != DialectProfile.ADBLOCK_BASE) {
            AdblockNetworkTranscoder.Result result = adblockNetworkTranscoder.transcode(
                    network,
                    record.raw(),
                    record.sourceDialect(),
                    output.dialect()
            );
            return result.content().isPresent()
                    ? ConversionResult.success(result.content().orElseThrow())
                    : ConversionResult.unsupported(
                            ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                            result.reason()
                    );
        }
        if (network.portable().isEmpty()) {
            return ConversionResult.unsupported(
                    ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "无法识别该网络规则的可转换语义，未执行可能改变匹配结果的转换"
            );
        }
        return applyPolicy(
                encodeCanonical(network.portable().orElseThrow(), output),
                allowNarrowing,
                allowBroadening
        );
    }

    private static ConversionResult applyPolicy(
            ConversionResult result,
            boolean allowNarrowing,
            boolean allowBroadening
    ) {
        if (result.status() == ConversionStatus.NARROWING && !allowNarrowing) {
            return ConversionResult.unsupported(
                    ConversionFailure.POLICY_REJECTED,
                    "配置禁止缩小匹配范围: " + result.reason()
            );
        }
        if (result.status() == ConversionStatus.BROADENING && !allowBroadening) {
            return ConversionResult.unsupported(
                    ConversionFailure.POLICY_REJECTED,
                    "配置禁止放大匹配范围: " + result.reason()
            );
        }
        return result;
    }

    private static ConversionResult encodeCanonical(
            CanonicalRule rule,
            BuildPlan.OutputSpec output
    ) {
        return switch (output.format()) {
            case EASYLIST -> encodeEasylist(rule, output.dialect());
            case DNS -> encodeDns(rule, output.dialect());
            case HOSTS -> encodeHosts(rule);
            case DNSMASQ -> encodeDnsmasq(rule);
            case SMARTDNS -> encodeSmartDns(rule);
            case CLASH -> encodeClash(rule, output.clashDialect());
            case SING_BOX -> encodeSingBox(rule);
        };
    }

    private static Optional<String> passthrough(
            RuleRecord record,
            BuildPlan.OutputSpec output
    ) {
        if (record.sourceProfile().equals(output.profile())
                && record.sourceSyntax() != RuleRecord.SourceSyntax.CANONICAL) {
            return Optional.of(record.raw());
        }
        return Optional.empty();
    }

    private static String unsupportedSourceSyntax(
            RuleRecord.SourceSyntax syntax,
            BuildPlan.OutputSpec output
    ) {
        if (syntax == RuleRecord.SourceSyntax.CLASH_CLASSICAL) {
            return output.format().name + " 无法表达该 Clash classical 规则类型";
        }
        return "无法识别该规则的可转换语义，未执行可能改变匹配结果的转换";
    }

    static ConversionResult encodeEasylist(
            CanonicalRule rule,
            DialectProfile dialect
    ) {
        if (rule.action() == CanonicalRule.Action.REWRITE) {
            return ConversionResult.unsupported(
                    "EasyList 无法表达域名重写规则"
            );
        }
        Optional<String> modifierText = modifiers(rule.featureMask(), dialect);
        if (modifierText.isEmpty()) {
            return ConversionResult.unsupported(
                    dialect.name + " 无法表达该规则的修饰条件"
            );
        }
        String prefix = rule.action() == CanonicalRule.Action.ALLOW ? "@@" : "";
        String suffix = modifierText.orElseThrow();
        return switch (rule.matchType()) {
            case EXACT_DOMAIN -> ConversionResult.broadening(
                    prefix + "||" + rule.value() + "^" + suffix,
                    "EasyList 无法在不限定协议的情况下精确表达仅根域名"
            );
            case DOMAIN_SUFFIX -> ConversionResult.success(
                    prefix + "||" + rule.value() + "^" + suffix
            );
            case SUBDOMAINS_ONLY -> ConversionResult.success(
                    prefix + "||*." + rule.value() + "^" + suffix
            );
            case REGEX -> ConversionResult.success(
                    prefix + "/" + rule.value() + "/" + suffix
            );
            case URL_PATTERN -> ConversionResult.success(
                    prefix + rule.value() + suffix
            );
            case IP_CIDR -> ConversionResult.unsupported(
                    "EasyList 无法表达 IP CIDR"
            );
            case DOMAIN_KEYWORD, DOMAIN_REGEX -> ConversionResult.unsupported(
                    "EasyList 无法精确表达仅作用于域名的关键词或正则匹配"
            );
        };
    }

    static ConversionResult encodeDns(
            CanonicalRule rule,
            DialectProfile dialect
    ) {
        if (rule.action() == CanonicalRule.Action.REWRITE) {
            if (rule.matchType() != CanonicalRule.MatchType.EXACT_DOMAIN) {
                return ConversionResult.unsupported(
                        "AdGuard DNS 只能重写精确域名"
                );
            }
            return ConversionResult.success(
                    rule.destination().orElseThrow() + "\t" + rule.value()
            );
        }
        Optional<String> modifierText = modifiers(rule.featureMask(), dialect);
        if (modifierText.isEmpty()) {
            return ConversionResult.unsupported(
                    dialect.name + " 无法表达该规则的修饰条件"
            );
        }
        String prefix = rule.action() == CanonicalRule.Action.ALLOW ? "@@" : "";
        String suffix = modifierText.orElseThrow();
        return switch (rule.matchType()) {
            case EXACT_DOMAIN -> ConversionResult.success(
                    prefix + rule.value() + suffix
            );
            case DOMAIN_SUFFIX -> ConversionResult.success(
                    prefix + "||" + rule.value() + "^" + suffix
            );
            case SUBDOMAINS_ONLY -> ConversionResult.success(
                    prefix + "||*." + rule.value() + "^" + suffix
            );
            case REGEX -> ConversionResult.success(
                    prefix + "/" + rule.value() + "/" + suffix
            );
            case URL_PATTERN -> ConversionResult.unsupported(
                    "AdGuard DNS 不能精确表达 URL 匹配模式"
            );
            case IP_CIDR -> ConversionResult.unsupported(
                    "AdGuard DNS 无法表达 IP CIDR"
            );
            case DOMAIN_KEYWORD, DOMAIN_REGEX -> ConversionResult.unsupported(
                    "AdGuard DNS 无法稳定表达仅作用于域名的关键词或正则匹配"
            );
        };
    }

    static ConversionResult encodeHosts(CanonicalRule rule) {
        if (rule.action() == CanonicalRule.Action.ALLOW) {
            return ConversionResult.unsupported(
                    "hosts 无法表达放行规则"
            );
        }
        if (rule.matchType() != CanonicalRule.MatchType.EXACT_DOMAIN
                && rule.matchType() != CanonicalRule.MatchType.DOMAIN_SUFFIX) {
            return ConversionResult.unsupported(
                    "hosts 只能表达精确域名或域名后缀"
            );
        }
        String destination = rule.action() == CanonicalRule.Action.REWRITE
                ? rule.destination().orElseThrow()
                : "0.0.0.0";
        String content = destination + "\t" + rule.value();
        return rule.matchType() == CanonicalRule.MatchType.DOMAIN_SUFFIX
                ? ConversionResult.narrowing(
                        content,
                        "hosts 只能写入域名后缀规则的根域名"
                )
                : ConversionResult.success(content);
    }

    static ConversionResult encodeDnsmasq(CanonicalRule rule) {
        if (rule.action() == CanonicalRule.Action.ALLOW) {
            return ConversionResult.unsupported(
                    "dnsmasq address 规则无法表达放行规则"
            );
        }
        String destination = rule.action() == CanonicalRule.Action.REWRITE
                ? rule.destination().orElseThrow()
                : "";
        String content = "address=/" + rule.value() + "/" + destination;
        return switch (rule.matchType()) {
            case DOMAIN_SUFFIX -> ConversionResult.success(content);
            case EXACT_DOMAIN -> ConversionResult.broadening(
                    content,
                    "dnsmasq address 会同时匹配子域"
            );
            case SUBDOMAINS_ONLY -> ConversionResult.broadening(
                    content,
                    "dnsmasq address 会额外匹配根域名"
            );
            case REGEX, URL_PATTERN, IP_CIDR, DOMAIN_KEYWORD, DOMAIN_REGEX ->
                    ConversionResult.unsupported("dnsmasq 只能表达域名匹配规则");
        };
    }

    static ConversionResult encodeSmartDns(CanonicalRule rule) {
        if (rule.action() == CanonicalRule.Action.REWRITE) {
            return ConversionResult.unsupported(
                    "smartdns address 规则无法稳定表达域名重写"
            );
        }
        String domain = switch (rule.matchType()) {
            case EXACT_DOMAIN -> "-." + rule.value();
            case DOMAIN_SUFFIX -> rule.value();
            case SUBDOMAINS_ONLY -> "*." + rule.value();
            case REGEX, URL_PATTERN, IP_CIDR, DOMAIN_KEYWORD, DOMAIN_REGEX -> null;
        };
        if (domain == null) {
            return ConversionResult.unsupported("smartdns 只能表达域名匹配规则");
        }
        String control = rule.action() == CanonicalRule.Action.BLOCK ? "#" : "-";
        return ConversionResult.success(
                "address /" + domain + "/" + control
        );
    }

    static ConversionResult encodeClash(
            CanonicalRule rule,
            ClashDialect dialect
    ) {
        if (rule.action() != CanonicalRule.Action.BLOCK) {
            return ConversionResult.unsupported(
                    "Clash rule-provider 只能表达阻止规则的匹配条件"
            );
        }
        String content = switch (dialect) {
            case DOMAIN -> switch (rule.matchType()) {
                case EXACT_DOMAIN -> rule.value();
                case DOMAIN_SUFFIX -> "+." + rule.value();
                case SUBDOMAINS_ONLY -> "." + rule.value();
                case REGEX, URL_PATTERN, IP_CIDR, DOMAIN_KEYWORD, DOMAIN_REGEX -> null;
            };
            case IPCIDR -> rule.matchType() == CanonicalRule.MatchType.IP_CIDR
                    ? rule.value()
                    : null;
            case CLASSICAL -> switch (rule.matchType()) {
                case EXACT_DOMAIN -> "DOMAIN," + rule.value();
                case DOMAIN_SUFFIX -> "DOMAIN-SUFFIX," + rule.value();
                case IP_CIDR -> (rule.value().contains(":") ? "IP-CIDR6," : "IP-CIDR,")
                        + rule.value();
                case DOMAIN_KEYWORD -> "DOMAIN-KEYWORD," + rule.value();
                case DOMAIN_REGEX -> "DOMAIN-REGEX," + rule.value();
                case SUBDOMAINS_ONLY, REGEX, URL_PATTERN -> null;
            };
        };
        if (content == null) {
            return ConversionResult.unsupported(
                    "Clash " + dialect.name + " 方言无法表达该匹配类型"
            );
        }
        return ConversionResult.success(
                "  - '" + content.replace("'", "''") + "'"
        );
    }

    static ConversionResult encodeSingBox(CanonicalRule rule) {
        if (rule.action() != CanonicalRule.Action.BLOCK) {
            return ConversionResult.unsupported(
                    "sing-box rule-set 只能表达阻止规则的匹配条件"
            );
        }
        String field = switch (rule.matchType()) {
            case EXACT_DOMAIN -> "domain";
            case DOMAIN_SUFFIX -> "domain_suffix";
            case SUBDOMAINS_ONLY -> "domain_suffix";
            case IP_CIDR -> "ip_cidr";
            case DOMAIN_KEYWORD -> "domain_keyword";
            case DOMAIN_REGEX -> "domain_regex";
            case REGEX, URL_PATTERN -> null;
        };
        if (field == null) {
            return ConversionResult.unsupported(
                    "sing-box rule-set 无法表达 URL 匹配模式"
            );
        }
        String value = rule.matchType() == CanonicalRule.MatchType.SUBDOMAINS_ONLY
                ? "." + rule.value()
                : rule.value();
        return ConversionResult.success(Json.singleValueRule(field, value));
    }

    private static Optional<String> modifiers(long featureMask, DialectProfile dialect) {
        if (featureMask == 0) {
            return Optional.of("");
        }
        if ((featureMask & CanonicalRule.FEATURE_ALL) != 0 && dialect != DialectProfile.UBO) {
            return Optional.empty();
        }
        if ((featureMask & CanonicalRule.FEATURE_IMPORTANT) != 0
                && dialect != DialectProfile.UBO
                && dialect != DialectProfile.ADGUARD) {
            return Optional.empty();
        }
        StringBuilder result = new StringBuilder("$");
        if ((featureMask & CanonicalRule.FEATURE_IMPORTANT) != 0) {
            result.append("important");
        }
        if ((featureMask & CanonicalRule.FEATURE_ALL) != 0) {
            if (result.length() > 1) {
                result.append(',');
            }
            result.append("all");
        }
        return Optional.of(result.toString());
    }

    public enum ConversionStatus {
        EXACT("exact"),
        NARROWING("narrowing"),
        BROADENING("broadening"),
        UNSUPPORTED("unsupported");

        public final String name;

        ConversionStatus(String name) {
            this.name = name;
        }
    }

    public enum ConversionFailure {
        TARGET_FORMAT_UNSUPPORTED("target-format-unsupported"),
        TARGET_DIALECT_UNSUPPORTED("target-dialect-unsupported"),
        TARGET_CAPABILITY_UNSUPPORTED("target-capability-unsupported"),
        SCRIPTLET_UNSUPPORTED("scriptlet-unsupported"),
        TRANSCODER_UNAVAILABLE("transcoder-unavailable"),
        SOURCE_RULE_UNRECOGNIZED("source-rule-unrecognized"),
        POLICY_REJECTED("policy-rejected"),
        SEMANTICS_UNREPRESENTABLE("semantics-unrepresentable");

        public final String name;

        ConversionFailure(String name) {
            this.name = name;
        }
    }

    public sealed interface ConversionResult permits Converted, Unsupported {

        ConversionStatus status();

        String reason();

        default Optional<String> content() {
            return this instanceof Converted converted
                    ? Optional.of(converted.value())
                    : Optional.empty();
        }

        default Optional<ConversionFailure> failure() {
            return this instanceof Unsupported unsupported
                    ? Optional.of(unsupported.category())
                    : Optional.empty();
        }

        static ConversionResult success(String content) {
            return new Converted(ConversionStatus.EXACT, content, "");
        }

        static ConversionResult narrowing(
                String content,
                String reason
        ) {
            return new Converted(ConversionStatus.NARROWING, content, reason);
        }

        static ConversionResult broadening(
                String content,
                String reason
        ) {
            return new Converted(ConversionStatus.BROADENING, content, reason);
        }

        static ConversionResult unsupported(String reason) {
            return unsupported(ConversionFailure.SEMANTICS_UNREPRESENTABLE, reason);
        }

        static ConversionResult unsupported(
                ConversionFailure failure,
                String reason
        ) {
            return new Unsupported(failure, reason);
        }
    }

    public record Converted(
            ConversionStatus status,
            String value,
            String reason
    ) implements ConversionResult {

        public Converted {
            Objects.requireNonNull(status, "status 不能为空");
            Objects.requireNonNull(value, "value 不能为空");
            Objects.requireNonNull(reason, "reason 不能为空");
            if (status == ConversionStatus.UNSUPPORTED) {
                throw new IllegalArgumentException("成功转换不得使用 UNSUPPORTED 状态");
            }
        }
    }

    public record Unsupported(
            ConversionFailure category,
            String reason
    ) implements ConversionResult {

        public Unsupported {
            Objects.requireNonNull(category, "category 不能为空");
            Objects.requireNonNull(reason, "reason 不能为空");
        }

        @Override
        public ConversionStatus status() {
            return ConversionStatus.UNSUPPORTED;
        }
    }
}

package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class RuleEncoder {

    private static final Set<String> ADGUARD_COMPATIBLE_UBO_SCRIPTLETS = Set.of(
            "acs",
            "addEventListener-defuser",
            "aeld",
            "aopr",
            "aopw",
            "aost",
            "href-sanitizer",
            "json-prune",
            "no-fetch-if",
            "no-xhr-if",
            "noeval-if",
            "nostif",
            "remove-cookie",
            "rmnt",
            "set",
            "set-attr",
            "set-local-storage-item"
    );

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
        if (record.extended().isPresent()) {
            return encodeExtended(record, output);
        }
        if (record.canonical().isEmpty()) {
            return ConversionResult.unsupported(
                    unsupportedSourceSyntax(record.sourceSyntax(), output)
            );
        }

        CanonicalRule rule = record.canonical().orElseThrow();
        ConversionResult result = switch (output.format()) {
            case EASYLIST -> encodeEasylist(rule, output.dialect());
            case DNS -> encodeDns(rule, output.dialect());
            case HOSTS -> encodeHosts(rule);
            case DNSMASQ -> encodeDnsmasq(rule);
            case SMARTDNS -> encodeSmartDns(rule);
            case CLASH -> encodeClash(rule, output.clashDialect());
            case SING_BOX -> encodeSingBox(rule);
        };
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

    String headerPrefix(RuleFormat format) {
        return switch (format) {
            case EASYLIST, DNS -> "! ";
            case HOSTS, DNSMASQ, SMARTDNS, CLASH -> "# ";
            case SING_BOX -> "";
        };
    }

    Optional<String> fixedHeader(RuleFormat format) {
        return format == RuleFormat.CLASH ? Optional.of("payload:") : Optional.empty();
    }

    private static Optional<String> passthrough(
            RuleRecord record,
            BuildPlan.OutputSpec output
    ) {
        if (record.sourceFormat() == output.format()
                && record.sourceDialect() == output.dialect()
                && record.sourceClashDialect() == output.clashDialect()
                && record.sourceSyntax() != RuleRecord.SourceSyntax.CANONICAL
                && supports(record.sourceSyntax(), output.dialect())) {
            return Optional.of(record.raw());
        }
        return Optional.empty();
    }

    private static ConversionResult encodeExtended(
            RuleRecord record,
            BuildPlan.OutputSpec output
    ) {
        if (output.format() != RuleFormat.EASYLIST) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_FORMAT_UNSUPPORTED,
                    output.format().name + " 只能表达网络或域名匹配规则，无法表达页面级扩展规则"
            );
        }

        AdblockExtendedRule rule = record.extended().orElseThrow();
        return switch (rule.syntax()) {
            case COSMETIC -> encodeCommonCosmetic(rule, output.dialect());
            case CSS_INJECTION -> encodeCssInjection(rule, output.dialect());
            case UBO_SCRIPTLET -> encodeUboScriptlet(record.raw(), rule, output.dialect());
            case ADGUARD_SCRIPTLET -> output.dialect() == DialectProfile.ADGUARD
                    ? ConversionResult.success(record.raw())
                    : unsupportedDialect("AdGuard scriptlet", output.dialect());
            case ABP_SNIPPET -> output.dialect() == DialectProfile.ABP
                    ? ConversionResult.success(record.raw())
                    : unsupportedDialect("ABP snippet", output.dialect());
            case UBO_HTML -> encodeUboHtml(record.raw(), rule, output.dialect());
            case ADGUARD_HTML -> encodeAdguardHtml(record.raw(), rule, output.dialect());
            case ADGUARD_EXTENDED_COSMETIC -> output.dialect() == DialectProfile.ADGUARD
                    ? ConversionResult.success(record.raw())
                    : unsupportedDialect("AdGuard 扩展元素规则", output.dialect());
            case ADGUARD_JAVASCRIPT -> output.dialect() == DialectProfile.ADGUARD
                    ? ConversionResult.success(record.raw())
                    : unsupportedDialect("AdGuard JavaScript 规则", output.dialect());
            case DIALECT_SPECIFIC_EXTENSION -> ConversionResult.unsupported(
                    ConversionFailure.TRANSCODER_UNAVAILABLE,
                    "该扩展规则依赖源方言，无法确认其在目标方言中的语义"
            );
            case OPAQUE -> ConversionResult.unsupported(
                    ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "无法识别该规则的具体语法，未执行可能改变语义的转换"
            );
        };
    }

    private static ConversionResult encodeCommonCosmetic(
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ADBLOCK_BASE) {
            return unsupportedDialect("元素隐藏规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent() && dialect != DialectProfile.ADGUARD) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 无法表达该元素规则的非基础修饰条件"
            );
        }
        return ConversionResult.success(renderExtended(
                rule,
                rule.action() == AdblockExtendedRule.Action.APPLY ? "##" : "#@#",
                rule.body()
        ));
    }

    private static ConversionResult encodeCssInjection(
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect != DialectProfile.ADGUARD && dialect != DialectProfile.UBO) {
            return unsupportedDialect("CSS 注入规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent() && dialect != DialectProfile.ADGUARD) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 无法表达该 CSS 注入规则的 AdGuard 非基础修饰条件"
            );
        }
        return ConversionResult.success(renderExtended(
                rule,
                rule.action() == AdblockExtendedRule.Action.APPLY ? "#$#" : "#@$#",
                rule.body()
        ));
    }

    private static ConversionResult encodeUboScriptlet(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.UBO) {
            return ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.ADGUARD) {
            return unsupportedDialect("uBO scriptlet", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "AdGuard 无法稳定表达该 uBO scriptlet 的非基础修饰条件"
            );
        }
        if (rule.scriptletName().isEmpty()) {
            return ConversionResult.unsupported(
                    ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "无法识别 uBO scriptlet 名称，未执行不可靠的跨方言转换"
            );
        }
        String name = rule.scriptletName().orElseThrow();
        if (!ADGUARD_COMPATIBLE_UBO_SCRIPTLETS.contains(name)) {
            return ConversionResult.unsupported(
                    ConversionFailure.SCRIPTLET_UNSUPPORTED,
                    "AdGuard 尚未确认兼容 uBO scriptlet “" + name + "”"
            );
        }
        return ConversionResult.success(raw);
    }

    private static ConversionResult encodeUboHtml(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.UBO) {
            return ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.ADGUARD) {
            return unsupportedDialect("uBO HTML filtering 规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "AdGuard 无法稳定表达该 uBO HTML filtering 规则的非基础修饰条件"
            );
        }
        if (!rule.body().startsWith("^") || rule.body().length() == 1) {
            return ConversionResult.unsupported(
                    ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "uBO HTML filtering 规则缺少有效选择器"
            );
        }
        String marker = rule.action() == AdblockExtendedRule.Action.APPLY ? "$$" : "$@$";
        return ConversionResult.success(renderExtended(rule, marker, rule.body().substring(1)));
    }

    private static ConversionResult encodeAdguardHtml(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ADGUARD) {
            return ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.UBO) {
            return unsupportedDialect("AdGuard HTML filtering 规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 无法表达该 AdGuard HTML filtering 规则的非基础修饰条件"
            );
        }
        String body = rule.body();
        if (containsAdguardOnlyHtmlSyntax(body)) {
            return ConversionResult.unsupported(
                    ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "该 AdGuard HTML filtering 选择器包含 uBO 不支持的专用条件"
            );
        }
        String marker = rule.action() == AdblockExtendedRule.Action.APPLY ? "##^" : "#@#^";
        return ConversionResult.success(renderExtended(rule, marker, body));
    }

    private static boolean containsAdguardOnlyHtmlSyntax(String body) {
        return body.contains("[tag-content")
                || body.contains("[wildcard")
                || body.contains("[max-length")
                || body.contains("[min-length")
                || body.contains(":contains(")
                || body.contains(":-abp-contains(");
    }

    private static String renderExtended(
            AdblockExtendedRule rule,
            String marker,
            String body
    ) {
        return rule.nonBasicModifiers().orElse("") + rule.domains() + marker + body;
    }

    private static ConversionResult unsupportedDialect(String ruleType, DialectProfile dialect) {
        return ConversionResult.unsupported(
                ConversionFailure.TARGET_DIALECT_UNSUPPORTED,
                dialect.name + " 无法稳定表达" + ruleType
        );
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

    private static ConversionResult encodeEasylist(
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

    private static ConversionResult encodeDns(
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

    private static ConversionResult encodeHosts(CanonicalRule rule) {
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

    private static ConversionResult encodeDnsmasq(CanonicalRule rule) {
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

    private static ConversionResult encodeSmartDns(CanonicalRule rule) {
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

    private static ConversionResult encodeClash(
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

    private static ConversionResult encodeSingBox(CanonicalRule rule) {
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
        return ConversionResult.success(
                "    {\"" + field + "\":[\"" + escapeJson(value) + "\"]}"
        );
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static Optional<String> modifiers(long featureMask, DialectProfile dialect) {
        if (featureMask == 0) {
            return Optional.of("");
        }
        if ((featureMask & (1L << 1)) != 0 && dialect != DialectProfile.UBO) {
            return Optional.empty();
        }
        if ((featureMask & 1L) != 0
                && dialect != DialectProfile.UBO
                && dialect != DialectProfile.ADGUARD) {
            return Optional.empty();
        }
        StringBuilder result = new StringBuilder("$");
        if ((featureMask & 1L) != 0) {
            result.append("important");
        }
        if ((featureMask & (1L << 1)) != 0) {
            if (result.length() > 1) {
                result.append(',');
            }
            result.append("all");
        }
        return Optional.of(result.toString());
    }

    private static boolean supports(
            RuleRecord.SourceSyntax syntax,
            DialectProfile dialect
    ) {
        return switch (syntax) {
            case NETWORK, COSMETIC -> true;
            case ADGUARD_EXTENDED_COSMETIC,
                    ADGUARD_SCRIPTLET,
                    ADGUARD_HTML,
                    ADGUARD_JAVASCRIPT -> dialect == DialectProfile.ADGUARD;
            case CSS_INJECTION -> dialect == DialectProfile.ADGUARD || dialect == DialectProfile.UBO;
            case UBO_SCRIPTLET, UBO_HTML -> dialect == DialectProfile.UBO;
            case ABP_SNIPPET -> dialect == DialectProfile.ABP;
            case DIALECT_SPECIFIC_EXTENSION -> dialect == DialectProfile.ADBLOCK_BASE;
            case CLASH_CLASSICAL -> true;
            case CANONICAL, OPAQUE -> false;
        };
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

    public record ConversionResult(
            ConversionStatus status,
            Optional<String> content,
            Optional<ConversionFailure> failure,
            String reason
    ) {

        public ConversionResult {
            Objects.requireNonNull(status, "status 不能为空");
            Objects.requireNonNull(content, "content 不能为空");
            Objects.requireNonNull(failure, "failure 不能为空");
            Objects.requireNonNull(reason, "reason 不能为空");
            if (status == ConversionStatus.UNSUPPORTED
                    && (content.isPresent() || failure.isEmpty())) {
                throw new IllegalArgumentException("失败的转换必须包含错误分类且不得包含输出内容");
            }
            if (status != ConversionStatus.UNSUPPORTED
                    && (content.isEmpty() || failure.isPresent())) {
                throw new IllegalArgumentException("成功的转换必须包含输出内容且不得包含错误分类");
            }
        }

        static ConversionResult success(String content) {
            return new ConversionResult(
                    ConversionStatus.EXACT,
                    Optional.of(content),
                    Optional.empty(),
                    ""
            );
        }

        static ConversionResult narrowing(
                String content,
                String reason
        ) {
            return new ConversionResult(
                    ConversionStatus.NARROWING,
                    Optional.of(content),
                    Optional.empty(),
                    reason
            );
        }

        static ConversionResult broadening(
                String content,
                String reason
        ) {
            return new ConversionResult(
                    ConversionStatus.BROADENING,
                    Optional.of(content),
                    Optional.empty(),
                    reason
            );
        }

        static ConversionResult unsupported(String reason) {
            return unsupported(ConversionFailure.SEMANTICS_UNREPRESENTABLE, reason);
        }

        static ConversionResult unsupported(
                ConversionFailure failure,
                String reason
        ) {
            return new ConversionResult(
                    ConversionStatus.UNSUPPORTED,
                    Optional.empty(),
                    Optional.of(failure),
                    reason
            );
        }
    }
}

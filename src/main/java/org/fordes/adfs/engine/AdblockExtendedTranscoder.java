package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.selector.SelectorTranscoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class AdblockExtendedTranscoder {

    private static final Set<String> ADGUARD_COMPATIBLE_UBO_SCRIPTLETS = Set.of(
            "abort-current-script",
            "acs",
            "abort-on-property-read",
            "abort-on-property-write",
            "abort-on-stack-trace",
            "addEventListener-defuser",
            "aeld",
            "aopr",
            "aopw",
            "aost",
            "adjust-setInterval",
            "adjust-setTimeout",
            "amazon-apstag",
            "amazon_apstag",
            "call-nothrow",
            "close-window",
            "window-close-if",
            "cookie-remover",
            "disable-newtab-links",
            "evaldata-prune",
            "fingerprint2",
            "fingerprintjs3",
            "google-analytics_analytics",
            "google-analytics-ga",
            "googletagservices_gpt",
            "href-sanitizer",
            "json-edit",
            "json-prune",
            "json-edit-fetch-response",
            "json-prune-fetch-response",
            "json-edit-xhr-response",
            "json-prune-xhr-response",
            "m3u-prune",
            "noeval",
            "nowebrtc",
            "no-fetch-if",
            "prevent-fetch",
            "no-xhr-if",
            "noeval-if",
            "nostif",
            "no-setTimeout-if",
            "no-window-open-if",
            "prevent-canvas",
            "prevent-innerHTML",
            "no-requestAnimationFrame-if",
            "norafif",
            "prevent-refresh",
            "prevent-setInterval",
            "prevent-setTimeout",
            "prevent-window-open",
            "prevent-xhr",
            "remove-attr",
            "ra",
            "remove-class",
            "remove-cookie",
            "remove-node-text",
            "rmnt",
            "rpnt",
            "set",
            "set-attr",
            "set-constant",
            "set-cookie",
            "set-local-storage-item",
            "set-session-storage-item",
            "spoof-css",
            "trusted-replace-argument",
            "trusted-set-cookie",
            "xml-prune"
    );
    private static final Set<String> ADGUARD_COMPATIBLE_ABP_SNIPPETS = Set.of(
            "abort-current-inline-script",
            "abort-on-property-read",
            "abort-on-property-write",
            "cookie-remover",
            "json-prune",
            "log",
            "override-property-read",
            "prevent-listener",
            "strip-fetch-query-parameter"
    );
    private static final Map<String, String> ADGUARD_TO_UBO_SCRIPTLETS = Map.ofEntries(
            Map.entry("abort-current-inline-script", "abort-current-script"),
            Map.entry("abort-on-property-read", "abort-on-property-read"),
            Map.entry("abort-on-property-write", "abort-on-property-write"),
            Map.entry("abort-on-stack-trace", "abort-on-stack-trace"),
            Map.entry("adjust-setInterval", "adjust-setInterval"),
            Map.entry("adjust-setTimeout", "adjust-setTimeout"),
            Map.entry("amazon-apstag", "amazon_apstag"),
            Map.entry("call-nothrow", "call-nothrow"),
            Map.entry("close-window", "window-close-if"),
            Map.entry("disable-newtab-links", "disable-newtab-links"),
            Map.entry("evaldata-prune", "evaldata-prune"),
            Map.entry("fingerprintjs2", "fingerprint2"),
            Map.entry("fingerprintjs3", "fingerprintjs3"),
            Map.entry("google-analytics", "google-analytics_analytics"),
            Map.entry("google-analytics-ga", "google-analytics-ga"),
            Map.entry("googletagservices-gpt", "googletagservices_gpt"),
            Map.entry("href-sanitizer", "href-sanitizer"),
            Map.entry("json-edit", "json-edit"),
            Map.entry("json-prune", "json-prune"),
            Map.entry("json-edit-fetch-response", "json-edit-fetch-response"),
            Map.entry("json-prune-fetch-response", "json-prune-fetch-response"),
            Map.entry("json-edit-xhr-response", "json-edit-xhr-response"),
            Map.entry("json-prune-xhr-response", "json-prune-xhr-response"),
            Map.entry("m3u-prune", "m3u-prune"),
            Map.entry("noeval", "noeval"),
            Map.entry("nowebrtc", "nowebrtc"),
            Map.entry("prevent-addEventListener", "addEventListener-defuser"),
            Map.entry("prevent-canvas", "prevent-canvas"),
            Map.entry("prevent-eval-if", "noeval-if"),
            Map.entry("prevent-fetch", "prevent-fetch"),
            Map.entry("prevent-innerHTML", "prevent-innerHTML"),
            Map.entry("prevent-refresh", "prevent-refresh"),
            Map.entry("prevent-requestAnimationFrame", "no-requestAnimationFrame-if"),
            Map.entry("prevent-setInterval", "prevent-setInterval"),
            Map.entry("prevent-setTimeout", "no-setTimeout-if"),
            Map.entry("prevent-window-open", "no-window-open-if"),
            Map.entry("prevent-xhr", "no-xhr-if"),
            Map.entry("remove-attr", "remove-attr"),
            Map.entry("remove-class", "remove-class"),
            Map.entry("remove-cookie", "remove-cookie"),
            Map.entry("remove-node-text", "remove-node-text"),
            Map.entry("set-attr", "set-attr"),
            Map.entry("set-constant", "set-constant"),
            Map.entry("set-cookie", "set-cookie"),
            Map.entry("set-cookie-reload", "set-cookie"),
            Map.entry("set-local-storage-item", "set-local-storage-item"),
            Map.entry("set-session-storage-item", "set-session-storage-item"),
            Map.entry("spoof-css", "spoof-css"),
            Map.entry("xml-prune", "xml-prune")
    );

    RuleEncoder.ConversionResult transcode(
            String raw,
            DialectProfile sourceDialect,
            AdblockExtendedRule rule,
            BuildPlan.OutputSpec output
    ) {
        if (output.format() != RuleFormat.EASYLIST) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_FORMAT_UNSUPPORTED,
                    output.format().name + " 只能表达网络或域名匹配规则，无法表达页面级扩展规则"
            );
        }

        return switch (rule.syntax()) {
            case COSMETIC -> encodeCommonCosmetic(
                    raw,
                    rule,
                    sourceDialect,
                    output.dialect()
            );
            case ADGUARD_EXTENDED_COSMETIC -> encodeExtendedCosmetic(
                    raw,
                    rule,
                    output.dialect()
            );
            case CSS_INJECTION -> encodeCssInjection(
                    raw,
                    rule,
                    output.dialect()
            );
            case UBO_SCRIPTLET -> encodeUboScriptlet(raw, rule, output.dialect());
            case ADGUARD_SCRIPTLET -> encodeAdguardScriptlet(
                    raw,
                    rule,
                    output.dialect()
            );
            case ABP_SNIPPET -> encodeAbpSnippet(raw, rule, output.dialect());
            case UBO_HTML -> encodeUboHtml(raw, rule, output.dialect());
            case ADGUARD_HTML -> encodeAdguardHtml(raw, rule, output.dialect());
            case ADGUARD_JAVASCRIPT -> output.dialect() == DialectProfile.ADGUARD
                    ? RuleEncoder.ConversionResult.success(raw)
                    : unsupportedDialect("AdGuard JavaScript 规则", output.dialect());
            case DIALECT_SPECIFIC_EXTENSION -> RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TRANSCODER_UNAVAILABLE,
                    "该扩展规则依赖源方言，无法确认其在目标方言中的语义"
            );
        };
    }

    private static RuleEncoder.ConversionResult encodeCommonCosmetic(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile sourceDialect,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ADBLOCK_BASE) {
            return unsupportedDialect("元素隐藏规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent() && dialect != DialectProfile.ADGUARD) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 无法表达该元素规则的非基础修饰条件"
            );
        }
        if (rule.body().indexOf('{') >= 0
                && !isCommonStyleBlock(rule.body())
                && !isCommonRemoveAction(rule.body())) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 未确认兼容该 inline style 规则"
            );
        }
        if (sourceDialect == DialectProfile.UBO && dialect == DialectProfile.ABP) {
            Optional<SelectorTranscoder.Conversion> conversion = SelectorTranscoder.transcode(
                    rule.body(),
                    dialect
            );
            if (conversion.isEmpty()) {
                return RuleEncoder.ConversionResult.unsupported(
                        RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                        "ABP 无法无损表达该 uBO cosmetic action"
                );
            }
            SelectorTranscoder.Conversion converted = conversion.orElseThrow();
            String convertedBody = converted.remove()
                    ? converted.selector() + " { remove: true; }"
                    : converted.selector();
            return RuleEncoder.ConversionResult.success(renderExtended(
                    rule,
                    rule.action() == AdblockExtendedRule.Action.APPLY && converted.procedural()
                            ? "#?#"
                            : rule.action() == AdblockExtendedRule.Action.APPLY ? "##" : "#@#",
                    convertedBody
            ));
        }
        return RuleEncoder.ConversionResult.success(raw);
    }

    private static boolean isCommonStyleBlock(String body) {
        String candidate = body.trim();
        int opening = findUnquoted(candidate, '{');
        if (opening <= 0) {
            return false;
        }
        int closing = matchingBrace(candidate, opening);
        if (closing != candidate.length() - 1) {
            return false;
        }
        String selector = candidate.substring(0, opening).trim();
        String declarations = candidate.substring(opening + 1, closing).trim();
        return !selector.isEmpty()
                && !declarations.isEmpty()
                && declarations.indexOf(':') >= 0;
    }

    private static int findUnquoted(String value, char expected) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == expected) {
                return index;
            }
        }
        return -1;
    }

    private static int matchingBrace(String value, int opening) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = opening; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            } else if (character == '}' && depth < 0) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isCommonRemoveAction(String body) {
        int declarationStart = body.lastIndexOf('{');
        int declarationEnd = body.lastIndexOf('}');
        if (declarationStart < 0 || declarationEnd != body.length() - 1) {
            return false;
        }
        StringBuilder declaration = new StringBuilder(declarationEnd - declarationStart - 1);
        for (int index = declarationStart + 1; index < declarationEnd; index++) {
            char character = body.charAt(index);
            if (!Character.isWhitespace(character)) {
                declaration.append(Character.toLowerCase(character));
            }
        }
        String content = declaration.toString();
        return content.equals("remove:true;") || content.equals("remove:true");
    }

    private static RuleEncoder.ConversionResult encodeExtendedCosmetic(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (raw.contains("#$?#")
                || raw.contains("#@$?#")
                || rule.body().indexOf('{') >= 0) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 未确认兼容该扩展 CSS 规则"
            );
        }
        if (dialect == DialectProfile.ADGUARD) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 无法表达扩展元素规则的非基础修饰条件"
            );
        }
        Optional<SelectorTranscoder.Conversion> conversion = SelectorTranscoder.transcode(
                rule.body(),
                dialect
        );
        if (conversion.isEmpty()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    dialect.name + " 无法无损表达该扩展选择器"
            );
        }
        SelectorTranscoder.Conversion converted = conversion.orElseThrow();
        String body = converted.remove()
                ? converted.selector() + " { remove: true; }"
                : converted.selector();
        if (dialect == DialectProfile.ABP
                && rule.action() == AdblockExtendedRule.Action.APPLY
                && body.equals(rule.body())) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect == DialectProfile.ABP) {
            return RuleEncoder.ConversionResult.success(renderExtended(
                    rule,
                    rule.action() == AdblockExtendedRule.Action.APPLY && !converted.remove()
                            ? "#?#"
                            : rule.action() == AdblockExtendedRule.Action.APPLY ? "##" : "#@#",
                    body
            ));
        }
        if (dialect == DialectProfile.UBO) {
            return RuleEncoder.ConversionResult.success(renderExtended(
                    rule,
                    rule.action() == AdblockExtendedRule.Action.APPLY ? "##" : "#@#",
                    body
            ));
        }
        return unsupportedDialect("扩展元素规则", dialect);
    }

    private static RuleEncoder.ConversionResult encodeCssInjection(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect != DialectProfile.ADGUARD && dialect != DialectProfile.UBO) {
            return unsupportedDialect("CSS 注入规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent() && dialect != DialectProfile.ADGUARD) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 无法表达该 CSS 注入规则的 AdGuard 非基础修饰条件"
            );
        }
        return RuleEncoder.ConversionResult.success(raw);
    }

    private static boolean hasPositiveExtendedDomain(String domains) {
        for (String domain : domains.split(",", -1)) {
            String value = domain.trim();
            if (!value.isEmpty() && !value.startsWith("~")) {
                return true;
            }
        }
        return false;
    }

    private static RuleEncoder.ConversionResult encodeUboScriptlet(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.UBO) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.ADGUARD) {
            return unsupportedDialect("uBO scriptlet", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "AdGuard 无法稳定表达该 uBO scriptlet 的非基础修饰条件"
            );
        }
        if (rule.scriptletName().isEmpty()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "无法识别 uBO scriptlet 名称，未执行不可靠的跨方言转换"
            );
        }
        String name = rule.scriptletName().orElseThrow();
        if (name.endsWith(".js")) {
            name = name.substring(0, name.length() - 3);
        }
        if (!ADGUARD_COMPATIBLE_UBO_SCRIPTLETS.contains(name)) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SCRIPTLET_UNSUPPORTED,
                    "AdGuard 尚未确认兼容 uBO scriptlet “" + name + "”"
            );
        }
        return RuleEncoder.ConversionResult.success(raw);
    }

    private static RuleEncoder.ConversionResult encodeAdguardScriptlet(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ADGUARD) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.UBO) {
            return unsupportedDialect("AdGuard scriptlet", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 无法表达该 AdGuard scriptlet 的非基础修饰条件"
            );
        }
        if (rule.action() == AdblockExtendedRule.Action.APPLY
                && !hasPositiveExtendedDomain(rule.domains())) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 会忽略没有正向域名范围的通用 scriptlet 注入规则"
            );
        }
        if (rule.scriptletName().isEmpty()) {
            if (rule.action() == AdblockExtendedRule.Action.EXCEPT) {
                return RuleEncoder.ConversionResult.success(renderExtended(rule, "#@#", "+js()"));
            }
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "AdGuard scriptlet 缺少名称"
            );
        }
        String targetName = ADGUARD_TO_UBO_SCRIPTLETS.get(rule.scriptletName().orElseThrow());
        if (targetName == null) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SCRIPTLET_UNSUPPORTED,
                    "uBO 没有该 AdGuard scriptlet 的官方兼容项: "
                            + rule.scriptletName().orElseThrow()
            );
        }
        Optional<List<String>> arguments = simpleCallArguments(rule.body(), "//scriptlet(");
        if (arguments.isEmpty()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "AdGuard scriptlet 参数无法无损转换为 uBO 语法"
            );
        }
        List<String> values = arguments.orElseThrow();
        StringBuilder body = new StringBuilder("+js(").append(targetName);
        for (int index = 1; index < values.size(); index++) {
            body.append(", ").append(values.get(index).replace(",", "\\,"));
        }
        body.append(')');
        return RuleEncoder.ConversionResult.success(renderExtended(
                rule,
                rule.action() == AdblockExtendedRule.Action.APPLY ? "##" : "#@#",
                body.toString()
        ));
    }

    private static RuleEncoder.ConversionResult encodeAbpSnippet(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ABP) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.ADGUARD) {
            return unsupportedDialect("ABP snippet", dialect);
        }
        if (rule.nonBasicModifiers().isPresent() || rule.body().indexOf(';') >= 0) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "仅转换单条且不含非基础修饰条件的 ABP snippet"
            );
        }
        String body = rule.body().trim();
        int end = 0;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        String name = body.substring(0, end);
        if (!ADGUARD_COMPATIBLE_ABP_SNIPPETS.contains(name)) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SCRIPTLET_UNSUPPORTED,
                    "AdGuard 未确认兼容 ABP snippet “" + name + "”"
            );
        }
        return RuleEncoder.ConversionResult.success(raw);
    }

    private static Optional<List<String>> simpleCallArguments(
            String body,
            String prefix
    ) {
        if (!body.startsWith(prefix) || !body.endsWith(")")) {
            return Optional.empty();
        }
        String content = body.substring(prefix.length(), body.length() - 1);
        List<String> arguments = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int index = 0; index <= content.length(); index++) {
            char character = index < content.length() ? content.charAt(index) : ',';
            if (character == '\\') {
                return Optional.empty();
            }
            if (quote == 0 && (character == '\'' || character == '"')) {
                quote = character;
            } else if (quote != 0 && character == quote) {
                quote = 0;
            } else if (quote == 0 && character == ',') {
                String value = content.substring(start, index).trim();
                if (value.length() >= 2
                        && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                        && value.charAt(value.length() - 1) == value.charAt(0)) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                arguments.add(value);
                start = index + 1;
            }
        }
        return quote == 0 && !arguments.isEmpty()
                ? Optional.of(List.copyOf(arguments))
                : Optional.empty();
    }

    private static RuleEncoder.ConversionResult encodeUboHtml(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.UBO) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.ADGUARD) {
            return unsupportedDialect("uBO HTML filtering 规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "AdGuard 无法稳定表达该 uBO HTML filtering 规则的非基础修饰条件"
            );
        }
        if (!rule.body().startsWith("^") || rule.body().length() == 1) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.SOURCE_RULE_UNRECOGNIZED,
                    "uBO HTML filtering 规则缺少有效选择器"
            );
        }
        String marker = rule.action() == AdblockExtendedRule.Action.APPLY ? "$$" : "$@$";
        return RuleEncoder.ConversionResult.success(renderExtended(rule, marker, rule.body().substring(1)));
    }

    private static RuleEncoder.ConversionResult encodeAdguardHtml(
            String raw,
            AdblockExtendedRule rule,
            DialectProfile dialect
    ) {
        if (dialect == DialectProfile.ADGUARD) {
            return RuleEncoder.ConversionResult.success(raw);
        }
        if (dialect != DialectProfile.UBO) {
            return unsupportedDialect("AdGuard HTML filtering 规则", dialect);
        }
        if (rule.nonBasicModifiers().isPresent()) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "uBO 无法表达该 AdGuard HTML filtering 规则的非基础修饰条件"
            );
        }
        String body = rule.body();
        if (containsAdguardOnlyHtmlSyntax(body)) {
            return RuleEncoder.ConversionResult.unsupported(
                    RuleEncoder.ConversionFailure.TARGET_CAPABILITY_UNSUPPORTED,
                    "该 AdGuard HTML filtering 选择器包含 uBO 不支持的专用条件"
            );
        }
        String marker = rule.action() == AdblockExtendedRule.Action.APPLY ? "##^" : "#@#^";
        return RuleEncoder.ConversionResult.success(renderExtended(rule, marker, body));
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

    private static RuleEncoder.ConversionResult unsupportedDialect(String ruleType, DialectProfile dialect) {
        return RuleEncoder.ConversionResult.unsupported(
                RuleEncoder.ConversionFailure.TARGET_DIALECT_UNSUPPORTED,
                dialect.name + " 无法稳定表达" + ruleType
        );
    }

}

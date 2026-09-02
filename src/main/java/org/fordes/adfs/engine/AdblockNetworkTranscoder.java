package org.fordes.adfs.engine;

import org.fordes.adfs.ast.NetworkAction;
import org.fordes.adfs.ast.NetworkAnchor;
import org.fordes.adfs.model.RuleBody;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class AdblockNetworkTranscoder {

    private static final Set<DialectProfile> ALL_DIALECTS = EnumSet.of(
            DialectProfile.ABP,
            DialectProfile.ADGUARD,
            DialectProfile.UBO
    );
    private static final Set<DialectProfile> ADGUARD_AND_UBO = EnumSet.of(
            DialectProfile.ADGUARD,
            DialectProfile.UBO
    );
    private static final Set<DialectProfile> ABP_AND_ADGUARD = EnumSet.of(
            DialectProfile.ABP,
            DialectProfile.ADGUARD
    );
    private static final Set<String> HTTP_METHODS = Set.of(
            "connect",
            "delete",
            "get",
            "head",
            "options",
            "patch",
            "post",
            "put"
    );
    private static final List<RedirectResource> REDIRECT_RESOURCES = List.of(
            resource("1x1-transparent.gif", "1x1.gif", "1x1-transparent-gif"),
            resource("2x2-transparent.png", "2x2.png", "2x2-transparent-png"),
            resource("3x2-transparent.png", "3x2.png", "3x2-transparent-png"),
            resource("32x32-transparent.png", "32x32.png", "32x32-transparent-png"),
            resource("amazon-apstag", "amazon_apstag.js", null),
            resource("click2load.html", "click2load.html", null),
            resource("fingerprintjs2", "fingerprint2.js", null),
            resource("fingerprintjs3", "fingerprint3.js", null),
            resource("google-analytics", "google-analytics_analytics.js", null),
            resource("google-analytics-ga", "google-analytics_ga.js", null),
            resource("google-ima3", "google-ima.js", null),
            resource("google-ima3-dai", "google-ima-dai.js", null),
            resource("googlesyndication-adsbygoogle", "googlesyndication_adsbygoogle.js", null),
            resource("googletagservices-gpt", "googletagservices_gpt.js", null),
            resource("noeval", "noeval-silent.js", null),
            resource("noopcss", "noop.css", "blank-css"),
            resource("noopframe", "noop.html", "blank-html"),
            resource("noopjs", "noop.js", "blank-js"),
            resource("noopjson", "noop.json", null),
            resource("nooptext", "noop.txt", "blank-text"),
            resource("noopmp3-0.1s", "noop-0.1s.mp3", "blank-mp3"),
            resource("noopmp4-1s", "noop-1s.mp4", "blank-mp4"),
            resource("noopvmap-1.0", "noop-vmap1.xml", null),
            resource("noopvast-2.0", "noop-vast2.xml", null),
            resource("noopvast-3.0", "noop-vast3.xml", null),
            resource("noopvast-4.0", "noop-vast4.xml", null),
            resource("prebid-ads", "prebid-ads.js", null),
            resource("prevent-bab2", "nobab2.js", null),
            resource("prevent-fab-3.2.0", "nofab.js", null),
            resource("prevent-popads-net", "popads.js", null),
            resource("scorecardresearch-beacon", "scorecardresearch_beacon.js", null),
            resource("set-popads-dummy", "popads-dummy.js", null),
            resource("empty", "empty", null)
    );
    private static final Map<String, RedirectResource> ADGUARD_REDIRECT_RESOURCES =
            redirectResources(DialectProfile.ADGUARD);
    private static final Map<String, RedirectResource> UBO_REDIRECT_RESOURCES =
            redirectResources(DialectProfile.UBO);
    private static final Map<String, RedirectResource> ABP_REDIRECT_RESOURCES =
            redirectResources(DialectProfile.ABP);

    Result transcode(
            RuleBody.AdblockNetwork network,
            String raw,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        Objects.requireNonNull(network, "network 不能为空");
        Objects.requireNonNull(raw, "raw 不能为空");
        Objects.requireNonNull(sourceDialect, "sourceDialect 不能为空");
        Objects.requireNonNull(targetDialect, "targetDialect 不能为空");

        if (!ALL_DIALECTS.contains(sourceDialect) || !ALL_DIALECTS.contains(targetDialect)) {
            return Result.unsupported("仅支持 ABP、AdGuard 和 uBO 网络规则之间的转换");
        }

        List<String> modifiers = new ArrayList<>(network.modifiers().size());
        boolean changed = false;
        for (RuleBody.AdblockNetwork.Modifier modifier : network.modifiers()) {
            Result result = transcodeModifier(
                    network,
                    modifier,
                    sourceDialect,
                    targetDialect
            );
            if (result.content().isEmpty()) {
                return Result.unsupported(result.reason());
            }
            String content = result.content().orElseThrow();
            modifiers.add(content);
            changed |= !content.equals(modifier.source());
        }

        Result pattern = transcodePattern(network, sourceDialect, targetDialect);
        if (pattern.content().isEmpty()) {
            return Result.unsupported(pattern.reason());
        }
        changed |= !pattern.content().orElseThrow().equals(
                network.unchangedPattern()
        );
        if (!changed) {
            return Result.success(raw);
        }
        if (modifiers.isEmpty()) {
            return Result.success(pattern.content().orElseThrow());
        }
        return Result.success(
                pattern.content().orElseThrow() + "$" + String.join(",", modifiers)
        );
    }

    private static Result transcodeModifier(
            RuleBody.AdblockNetwork rule,
            RuleBody.AdblockNetwork.Modifier modifier,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        String sourceName = modifier.name().toLowerCase(Locale.ROOT);
        String sourceModifier = modifier.source();
        Optional<ModifierAlias> alias = alias(sourceName);
        if (alias.isEmpty() || !alias.orElseThrow().sourceDialects().contains(sourceDialect)) {
            return Result.unsupported(
                    "未确认目标方言兼容网络修饰符 $" + sourceName
            );
        }

        ModifierAlias resolvedAlias = alias.orElseThrow();
        ModifierKind kind = resolvedAlias.kind();
        if (!kind.targetDialects().contains(targetDialect)) {
            return Result.unsupported(
                    targetDialect.name + " 不支持网络修饰符 $" + sourceName
            );
        }
        if ((kind.valueRequirement() == ValueRequirement.NONE && modifier.value().isPresent())
                || (kind.valueRequirement() == ValueRequirement.REQUIRED
                    && modifier.value().isEmpty())) {
            return Result.unsupported(
                    "网络修饰符 $" + sourceName + " 的值格式无法识别"
            );
        }
        if ((kind == ModifierKind.CSP || kind == ModifierKind.PERMISSIONS)
                && rule.action() == NetworkAction.BLOCK
                && modifier.value().isEmpty()) {
            return Result.unsupported(
                    "阻止规则的 $" + sourceName + " 必须提供值"
            );
        }
        if ((kind == ModifierKind.REDIRECT || kind == ModifierKind.REDIRECT_RULE)
                && rule.action() == NetworkAction.BLOCK
                && modifier.value().isEmpty()) {
            return Result.unsupported(
                    "阻止规则的 $" + sourceName + " 必须提供重定向资源"
            );
        }
        if ((kind == ModifierKind.CSP || kind == ModifierKind.PERMISSIONS)
                && targetDialect == DialectProfile.ABP
                && modifier.value().isEmpty()) {
            return Result.unsupported(
                    "未确认 ABP 支持无值的 $" + sourceName + " 例外规则"
            );
        }

        boolean negated = resolvedAlias.invertNegation()
                ? !modifier.negated()
                : modifier.negated();
        if (negated && !kind.negatable()) {
            return Result.unsupported(
                    "未确认目标方言支持否定修饰符 $~" + sourceName
            );
        }
        if (kind == ModifierKind.MATCH_CASE && targetDialect == DialectProfile.UBO && !rule.regex()) {
            return Result.unsupported("uBO 仅对正则网络规则支持 $match-case");
        }
        if (kind == ModifierKind.DOCUMENT
                && targetDialect == DialectProfile.ABP
                && rule.action() == NetworkAction.BLOCK) {
            return Result.unsupported("ABP 的 $document 仅支持例外规则");
        }
        if (kind == ModifierKind.DOCUMENT
                && rule.action() == NetworkAction.ALLOW
                && (sourceDialect == DialectProfile.UBO || targetDialect == DialectProfile.UBO)
                && isWholePageDocumentException(rule)) {
            return Result.unsupported(
                    "uBO 与 ABP/AdGuard 的 $document 整页例外语义不等价"
            );
        }
        if (kind == ModifierKind.REDIRECT
                && targetDialect == DialectProfile.ABP
                && !isValidAbpRewriteRule(rule)) {
            return Result.unsupported(
                    "ABP $rewrite 要求 pattern 为 * 或 || 开头、包含正向 $domain，且不能使用正向 $third-party"
            );
        }

        StringBuilder rendered = new StringBuilder();
        if (negated) {
            rendered.append('~');
        }
        String targetName = targetModifierName(kind, targetDialect);
        rendered.append(targetName);
        boolean valueUnchanged = true;
        if (modifier.value().isPresent()) {
            String value = modifier.value().orElseThrow();
            Result valueResult = transcodeValue(
                    kind,
                    sourceName,
                    value,
                    sourceDialect,
                    targetDialect
            );
            if (valueResult.content().isEmpty()) {
                return Result.unsupported(valueResult.reason());
            }
            String convertedValue = valueResult.content().orElseThrow();
            valueUnchanged = convertedValue.equals(value);
            rendered.append('=').append(convertedValue);
        }
        if (!resolvedAlias.invertNegation()
                && sourceName.equals(targetName)
                && valueUnchanged) {
            return Result.success(sourceModifier);
        }
        return Result.success(rendered.toString());
    }

    private static Result transcodeValue(
            ModifierKind kind,
            String sourceName,
            String value,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        if (kind == ModifierKind.DOMAIN
                && targetDialect == DialectProfile.ABP
                && !isAbpDomainValue(value)) {
            return Result.unsupported(
                    "ABP 无法表达该 $" + sourceName + " 域名范围: " + value
            );
        }
        if (kind == ModifierKind.HEADER) {
            return transcodeHeaderValue(value, sourceDialect, targetDialect);
        }
        if (kind == ModifierKind.METHOD && !isMethodValue(value)) {
            return Result.unsupported("$method 值无效: " + value);
        }
        if (kind == ModifierKind.TO
                && targetDialect == DialectProfile.ADGUARD
                && value.indexOf('/') >= 0) {
            return Result.unsupported(
                    "AdGuard 无法表达该 $to 正则域名范围: " + value
            );
        }
        if (kind == ModifierKind.DENYALLOW && !isPlainDomainList(value, false)) {
            return Result.unsupported("$denyallow 域名列表无法无损转换: " + value);
        }
        if (kind == ModifierKind.REDIRECT || kind == ModifierKind.REDIRECT_RULE) {
            return transcodeRedirectValue(
                    sourceName,
                    value,
                    sourceDialect,
                    targetDialect
            );
        }
        return Result.success(value);
    }

    private static Result transcodeRedirectValue(
            String sourceName,
            String value,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        DialectProfile resourceDialect = sourceName.equals("rewrite")
                ? DialectProfile.ABP
                : sourceDialect;
        String resourceName = value;
        if (resourceDialect == DialectProfile.ABP) {
            String prefix = "abp-resource:";
            if (!resourceName.startsWith(prefix)) {
                return Result.unsupported(
                        "$rewrite 仅转换 abp-resource 内部资源: " + value
                );
            }
            resourceName = resourceName.substring(prefix.length());
        } else if (sourceDialect == DialectProfile.UBO && hasRedirectPriority(resourceName)) {
            return Result.unsupported(
                    "uBO redirect 资源优先级无法在目标方言中无损保留: " + value
            );
        }
        if (resourceDialect == DialectProfile.UBO && resourceName.equals("none")) {
            return Result.unsupported(
                    "AdGuard 和 ABP 不支持 uBO redirect 资源 none"
            );
        }

        RedirectResource resource = resources(resourceDialect).get(resourceName);
        if (resource == null) {
            return Result.unsupported(
                    "官方兼容表未收录该 redirect 资源: " + value
            );
        }
        String targetName = resource.name(targetDialect);
        if (targetName == null) {
            return Result.unsupported(
                    targetDialect.name + " 没有该 redirect 资源的等价项: " + value
            );
        }
        return Result.success(
                targetDialect == DialectProfile.ABP
                        ? "abp-resource:" + targetName
                        : targetName
        );
    }

    private static Result transcodeHeaderValue(
            String value,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        if (sourceDialect == DialectProfile.ABP && targetDialect != DialectProfile.ABP) {
            int separator = value.indexOf('=');
            if (separator < 0) {
                return Result.success(value);
            }
            String content = value.substring(separator + 1);
            if (containsIgnoreCase(content, "\\x2c")) {
                return Result.unsupported(
                        "ABP $header 的 \\x2c 转义无法在目标方言中直接保留"
                );
            }
            return Result.success(
                    value.substring(0, separator) + ':' + content
            );
        }
        if (sourceDialect != DialectProfile.ABP && targetDialect == DialectProfile.ABP) {
            int separator = value.indexOf(':');
            if (separator < 0) {
                return Result.success(value);
            }
            String content = value.substring(separator + 1);
            if (content.startsWith("/")
                    || sourceDialect == DialectProfile.UBO && content.startsWith("~")
                    || content.contains("\\,")) {
                return Result.unsupported(
                        "ABP 无法无损表达该 $header 匹配值: " + content
                );
            }
            return Result.success(
                    value.substring(0, separator) + '=' + content
            );
        }
        return Result.success(value);
    }

    private static Result transcodePattern(
            RuleBody.AdblockNetwork rule,
            DialectProfile sourceDialect,
            DialectProfile targetDialect
    ) {
        String unchanged = rule.unchangedPattern();
        if (rule.regex()
                || rule.leftAnchor() != NetworkAnchor.NONE
                || rule.rightAnchor()) {
            return Result.success(unchanged);
        }

        String value = rule.pattern();
        if (RuleParser.normalizeDomain(value).isEmpty()) {
            return Result.success(unchanged);
        }

        String replacement;
        if (sourceDialect == DialectProfile.UBO && targetDialect != DialectProfile.UBO) {
            if (!hasPositiveRequestType(rule)) {
                return Result.unsupported(
                        "uBO 裸域名规则的主文档严格阻止语义无法用单条目标规则精确表达"
                );
            }
            replacement = "||" + value + "^";
        } else if (sourceDialect != DialectProfile.UBO && targetDialect == DialectProfile.UBO) {
            replacement = value + "*";
        } else {
            return Result.success(unchanged);
        }
        return Result.success(
                rule.prefix()
                        + replacement
                        + rule.suffix()
        );
    }

    private static Optional<ModifierAlias> alias(String name) {
        if (!name.isEmpty() && name.codePoints().allMatch(character -> character == '_')) {
            return alias(ModifierKind.NOOP, ADGUARD_AND_UBO, false);
        }
        return switch (name) {
            case "script" -> common(ModifierKind.SCRIPT);
            case "image" -> common(ModifierKind.IMAGE);
            case "stylesheet" -> common(ModifierKind.STYLESHEET);
            case "css" -> alias(ModifierKind.STYLESHEET, ADGUARD_AND_UBO, false);
            case "object" -> common(ModifierKind.OBJECT);
            case "xmlhttprequest" -> common(ModifierKind.XMLHTTPREQUEST);
            case "xhr" -> alias(ModifierKind.XMLHTTPREQUEST, ADGUARD_AND_UBO, false);
            case "subdocument" -> common(ModifierKind.SUBDOCUMENT);
            case "frame" -> alias(ModifierKind.SUBDOCUMENT, ADGUARD_AND_UBO, false);
            case "ping" -> common(ModifierKind.PING);
            case "websocket" -> common(ModifierKind.WEBSOCKET);
            case "other" -> common(ModifierKind.OTHER);
            case "font" -> common(ModifierKind.FONT);
            case "media" -> common(ModifierKind.MEDIA);
            case "popup" -> common(ModifierKind.POPUP);
            case "third-party" -> common(ModifierKind.THIRD_PARTY);
            case "3p" -> alias(ModifierKind.THIRD_PARTY, ADGUARD_AND_UBO, false);
            case "first-party" -> alias(
                    ModifierKind.THIRD_PARTY,
                    Set.of(DialectProfile.UBO),
                    true
            );
            case "1p" -> alias(ModifierKind.THIRD_PARTY, ADGUARD_AND_UBO, true);
            case "domain" -> common(ModifierKind.DOMAIN);
            case "from" -> alias(ModifierKind.DOMAIN, ADGUARD_AND_UBO, false);
            case "csp" -> common(ModifierKind.CSP);
            case "header" -> common(ModifierKind.HEADER);
            case "method" -> alias(ModifierKind.METHOD, ADGUARD_AND_UBO, false);
            case "to" -> alias(ModifierKind.TO, ADGUARD_AND_UBO, false);
            case "denyallow" -> alias(ModifierKind.DENYALLOW, ADGUARD_AND_UBO, false);
            case "removeparam" -> alias(ModifierKind.REMOVEPARAM, ADGUARD_AND_UBO, false);
            case "queryprune" -> alias(
                    ModifierKind.REMOVEPARAM,
                    Set.of(DialectProfile.UBO),
                    false
            );
            case "reason" -> alias(ModifierKind.REASON, ADGUARD_AND_UBO, false);
            case "permissions" -> alias(ModifierKind.PERMISSIONS, ADGUARD_AND_UBO, false);
            case "replace" -> alias(ModifierKind.REPLACE, ADGUARD_AND_UBO, false);
            case "urltransform" -> alias(
                    ModifierKind.URLTRANSFORM,
                    Set.of(DialectProfile.ADGUARD),
                    false
            );
            case "uritransform" -> alias(
                    ModifierKind.URLTRANSFORM,
                    Set.of(DialectProfile.UBO),
                    false
            );
            case "strict-first-party" -> alias(
                    ModifierKind.STRICT_FIRST_PARTY,
                    Set.of(DialectProfile.ADGUARD),
                    false
            );
            case "strict1p" -> alias(
                    ModifierKind.STRICT_FIRST_PARTY,
                    Set.of(DialectProfile.UBO),
                    false
            );
            case "strict-third-party" -> alias(
                    ModifierKind.STRICT_THIRD_PARTY,
                    Set.of(DialectProfile.ADGUARD),
                    false
            );
            case "strict3p" -> alias(
                    ModifierKind.STRICT_THIRD_PARTY,
                    Set.of(DialectProfile.UBO),
                    false
            );
            case "redirect" -> alias(ModifierKind.REDIRECT, ADGUARD_AND_UBO, false);
            case "rewrite" -> alias(ModifierKind.REDIRECT, ABP_AND_ADGUARD, false);
            case "redirect-rule" -> alias(
                    ModifierKind.REDIRECT_RULE,
                    ADGUARD_AND_UBO,
                    false
            );
            case "empty" -> alias(ModifierKind.EMPTY, ADGUARD_AND_UBO, false);
            case "mp4" -> alias(ModifierKind.MP4, ADGUARD_AND_UBO, false);
            case "badfilter" -> common(ModifierKind.BADFILTER);
            case "inline-script" -> alias(ModifierKind.INLINE_SCRIPT, ADGUARD_AND_UBO, false);
            case "inline-font" -> alias(ModifierKind.INLINE_FONT, ADGUARD_AND_UBO, false);
            case "match-case" -> common(ModifierKind.MATCH_CASE);
            case "important" -> alias(ModifierKind.IMPORTANT, ADGUARD_AND_UBO, false);
            case "all" -> alias(ModifierKind.ALL, ADGUARD_AND_UBO, false);
            case "document" -> common(ModifierKind.DOCUMENT);
            case "doc" -> alias(ModifierKind.DOCUMENT, ADGUARD_AND_UBO, false);
            case "elemhide" -> common(ModifierKind.ELEMHIDE);
            case "ehide" -> alias(ModifierKind.ELEMHIDE, ADGUARD_AND_UBO, false);
            case "generichide" -> common(ModifierKind.GENERICHIDE);
            case "ghide" -> alias(ModifierKind.GENERICHIDE, ADGUARD_AND_UBO, false);
            case "genericblock" -> common(ModifierKind.GENERICBLOCK);
            case "specifichide" -> alias(ModifierKind.SPECIFICHIDE, ADGUARD_AND_UBO, false);
            case "shide" -> alias(ModifierKind.SPECIFICHIDE, ADGUARD_AND_UBO, false);
            default -> Optional.empty();
        };
    }

    private static Optional<ModifierAlias> common(ModifierKind kind) {
        return alias(kind, ALL_DIALECTS, false);
    }

    private static Optional<ModifierAlias> alias(
            ModifierKind kind,
            Set<DialectProfile> sourceDialects,
            boolean invertNegation
    ) {
        return Optional.of(new ModifierAlias(kind, sourceDialects, invertNegation));
    }

    private static String targetModifierName(
            ModifierKind kind,
            DialectProfile targetDialect
    ) {
        if (kind == ModifierKind.REDIRECT && targetDialect == DialectProfile.ABP) {
            return "rewrite";
        }
        if (kind == ModifierKind.URLTRANSFORM && targetDialect == DialectProfile.UBO) {
            return "uritransform";
        }
        if (kind == ModifierKind.STRICT_FIRST_PARTY && targetDialect == DialectProfile.UBO) {
            return "strict1p";
        }
        if (kind == ModifierKind.STRICT_THIRD_PARTY && targetDialect == DialectProfile.UBO) {
            return "strict3p";
        }
        return kind.targetName();
    }

    private static RedirectResource resource(String adguard, String ubo, String abp) {
        return new RedirectResource(adguard, ubo, abp);
    }

    private static Map<String, RedirectResource> redirectResources(DialectProfile dialect) {
        Map<String, RedirectResource> resources = new HashMap<>();
        for (RedirectResource resource : REDIRECT_RESOURCES) {
            String name = resource.name(dialect);
            if (name != null) {
                resources.put(name, resource);
            }
        }
        if (dialect == DialectProfile.UBO) {
            addRedirectAlias(resources, "noopjs", "noop.js");
            addRedirectAlias(resources, "nooptext", "noop.txt");
        }
        return Map.copyOf(resources);
    }

    private static void addRedirectAlias(
            Map<String, RedirectResource> resources,
            String alias,
            String canonicalName
    ) {
        RedirectResource resource = resources.get(canonicalName);
        if (resource == null) {
            throw new IllegalStateException("缺少 redirect 资源: " + canonicalName);
        }
        resources.put(alias, resource);
    }

    private static Map<String, RedirectResource> resources(DialectProfile dialect) {
        return switch (dialect) {
            case ABP -> ABP_REDIRECT_RESOURCES;
            case ADGUARD -> ADGUARD_REDIRECT_RESOURCES;
            case UBO -> UBO_REDIRECT_RESOURCES;
            case ADBLOCK_BASE -> Map.of();
        };
    }

    private static boolean hasRedirectPriority(String value) {
        int separator = value.lastIndexOf(':');
        if (separator < 0 || separator == value.length() - 1) {
            return false;
        }
        String priority = value.substring(separator + 1);
        int start = priority.startsWith("-") ? 1 : 0;
        return start < priority.length()
                && priority.substring(start).codePoints().allMatch(Character::isDigit);
    }

    private static boolean isAbpDomainValue(String value) {
        return value.indexOf('*') < 0
                && value.indexOf('/') < 0
                && isPlainDomainList(value, true);
    }

    private static boolean isPlainDomainList(String value, boolean allowExcluded) {
        if (value.isBlank()) {
            return false;
        }
        for (String item : value.split("\\|", -1)) {
            boolean excluded = item.startsWith("~");
            if (excluded && !allowExcluded) {
                return false;
            }
            String domain = excluded ? item.substring(1) : item;
            if (RuleParser.normalizeDomain(domain).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMethodValue(String value) {
        Boolean negated = null;
        for (String item : value.split("\\|", -1)) {
            boolean currentNegated = item.startsWith("~");
            String method = currentNegated ? item.substring(1) : item;
            if (!HTTP_METHODS.contains(method)) {
                return false;
            }
            if (negated != null && negated != currentNegated) {
                return false;
            }
            negated = currentNegated;
        }
        return negated != null;
    }

    private static boolean containsIgnoreCase(String value, String expected) {
        return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private static boolean hasPositiveRequestType(RuleBody.AdblockNetwork rule) {
        for (RuleBody.AdblockNetwork.Modifier modifier : rule.modifiers()) {
            String name = modifier.name().toLowerCase(Locale.ROOT);
            Optional<ModifierAlias> alias = alias(name);
            if (!modifier.negated()
                    && alias.isPresent()
                    && alias.orElseThrow().kind().requestType()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWholePageDocumentException(RuleBody.AdblockNetwork rule) {
        if (rule.modifiers().size() != 1) {
            return false;
        }
        RuleBody.AdblockNetwork.Modifier modifier = rule.modifiers().get(0);
        return !modifier.negated()
                && alias(modifier.name().toLowerCase(Locale.ROOT))
                .map(ModifierAlias::kind)
                .filter(kind -> kind == ModifierKind.DOCUMENT)
                .isPresent();
    }

    private static boolean isValidAbpRewriteRule(RuleBody.AdblockNetwork rule) {
        if (rule.leftAnchor() != NetworkAnchor.DOMAIN
                && !rule.pattern().equals("*")) {
            return false;
        }
        boolean positiveDomain = false;
        for (RuleBody.AdblockNetwork.Modifier modifier : rule.modifiers()) {
            String name = modifier.name().toLowerCase(Locale.ROOT);
            Optional<ModifierAlias> alias = alias(name);
            if (alias.isEmpty()) {
                continue;
            }
            ModifierAlias resolved = alias.orElseThrow();
            boolean negated = resolved.invertNegation()
                    ? !modifier.negated()
                    : modifier.negated();
            if (resolved.kind() == ModifierKind.THIRD_PARTY && !negated) {
                return false;
            }
            if (resolved.kind() == ModifierKind.DOMAIN && modifier.value().isPresent()) {
                String value = modifier.value().orElseThrow();
                for (String domain : value.split("\\|", -1)) {
                    if (!domain.startsWith("~") && !domain.isBlank()) {
                        positiveDomain = true;
                    }
                }
            }
        }
        return positiveDomain;
    }

    private enum ModifierKind {
        SCRIPT("script", ALL_DIALECTS, ValueRequirement.NONE, true),
        IMAGE("image", ALL_DIALECTS, ValueRequirement.NONE, true),
        STYLESHEET("stylesheet", ALL_DIALECTS, ValueRequirement.NONE, true),
        OBJECT("object", ALL_DIALECTS, ValueRequirement.NONE, true),
        XMLHTTPREQUEST("xmlhttprequest", ALL_DIALECTS, ValueRequirement.NONE, true),
        SUBDOCUMENT("subdocument", ALL_DIALECTS, ValueRequirement.NONE, true),
        PING("ping", ALL_DIALECTS, ValueRequirement.NONE, true),
        WEBSOCKET("websocket", ALL_DIALECTS, ValueRequirement.NONE, true),
        OTHER("other", ALL_DIALECTS, ValueRequirement.NONE, true),
        FONT("font", ALL_DIALECTS, ValueRequirement.NONE, false),
        MEDIA("media", ALL_DIALECTS, ValueRequirement.NONE, true),
        POPUP("popup", ALL_DIALECTS, ValueRequirement.NONE, false),
        THIRD_PARTY("third-party", ALL_DIALECTS, ValueRequirement.NONE, true),
        DOMAIN("domain", ALL_DIALECTS, ValueRequirement.REQUIRED, false),
        CSP("csp", ALL_DIALECTS, ValueRequirement.OPTIONAL, false),
        HEADER("header", ALL_DIALECTS, ValueRequirement.REQUIRED, false),
        METHOD("method", ADGUARD_AND_UBO, ValueRequirement.REQUIRED, false),
        TO("to", ADGUARD_AND_UBO, ValueRequirement.REQUIRED, false),
        DENYALLOW("denyallow", ADGUARD_AND_UBO, ValueRequirement.REQUIRED, false),
        REMOVEPARAM("removeparam", ADGUARD_AND_UBO, ValueRequirement.OPTIONAL, false),
        REASON("reason", Set.of(DialectProfile.ADGUARD), ValueRequirement.REQUIRED, false),
        PERMISSIONS("permissions", ADGUARD_AND_UBO, ValueRequirement.OPTIONAL, false),
        REPLACE("replace", ADGUARD_AND_UBO, ValueRequirement.REQUIRED, false),
        URLTRANSFORM("urltransform", ADGUARD_AND_UBO, ValueRequirement.REQUIRED, false),
        STRICT_FIRST_PARTY(
                "strict-first-party",
                ADGUARD_AND_UBO,
                ValueRequirement.NONE,
                false
        ),
        STRICT_THIRD_PARTY(
                "strict-third-party",
                ADGUARD_AND_UBO,
                ValueRequirement.NONE,
                false
        ),
        REDIRECT("redirect", ALL_DIALECTS, ValueRequirement.OPTIONAL, false),
        REDIRECT_RULE("redirect-rule", ADGUARD_AND_UBO, ValueRequirement.OPTIONAL, false),
        EMPTY("empty", ADGUARD_AND_UBO, ValueRequirement.NONE, false),
        MP4("mp4", ADGUARD_AND_UBO, ValueRequirement.NONE, false),
        BADFILTER("badfilter", ALL_DIALECTS, ValueRequirement.NONE, false),
        INLINE_SCRIPT("inline-script", ADGUARD_AND_UBO, ValueRequirement.NONE, true),
        INLINE_FONT("inline-font", ADGUARD_AND_UBO, ValueRequirement.NONE, true),
        MATCH_CASE("match-case", ALL_DIALECTS, ValueRequirement.NONE, false),
        IMPORTANT("important", ADGUARD_AND_UBO, ValueRequirement.NONE, false),
        ALL("all", ADGUARD_AND_UBO, ValueRequirement.NONE, false),
        DOCUMENT("document", ALL_DIALECTS, ValueRequirement.NONE, true),
        ELEMHIDE("elemhide", ALL_DIALECTS, ValueRequirement.NONE, true),
        GENERICHIDE("generichide", ALL_DIALECTS, ValueRequirement.NONE, false),
        GENERICBLOCK("genericblock", ALL_DIALECTS, ValueRequirement.NONE, false),
        SPECIFICHIDE("specifichide", ADGUARD_AND_UBO, ValueRequirement.NONE, false),
        NOOP("_", ADGUARD_AND_UBO, ValueRequirement.NONE, false);

        private final String targetName;
        private final Set<DialectProfile> targetDialects;
        private final ValueRequirement valueRequirement;
        private final boolean negatable;

        ModifierKind(
                String targetName,
                Set<DialectProfile> targetDialects,
                ValueRequirement valueRequirement,
                boolean negatable
        ) {
            this.targetName = targetName;
            this.targetDialects = targetDialects;
            this.valueRequirement = valueRequirement;
            this.negatable = negatable;
        }

        String targetName() {
            return targetName;
        }

        Set<DialectProfile> targetDialects() {
            return targetDialects;
        }

        ValueRequirement valueRequirement() {
            return valueRequirement;
        }

        boolean negatable() {
            return negatable;
        }

        boolean requestType() {
            return switch (this) {
                case SCRIPT,
                        IMAGE,
                        STYLESHEET,
                        OBJECT,
                        XMLHTTPREQUEST,
                        SUBDOCUMENT,
                        PING,
                        WEBSOCKET,
                        OTHER,
                        FONT,
                        MEDIA,
                        POPUP,
                        DOCUMENT,
                        ALL -> true;
                case THIRD_PARTY,
                        DOMAIN,
                        CSP,
                        HEADER,
                        METHOD,
                        TO,
                        DENYALLOW,
                        REMOVEPARAM,
                        REASON,
                        PERMISSIONS,
                        REPLACE,
                        URLTRANSFORM,
                        STRICT_FIRST_PARTY,
                        STRICT_THIRD_PARTY,
                        REDIRECT,
                        REDIRECT_RULE,
                        EMPTY,
                        MP4,
                        BADFILTER,
                        INLINE_SCRIPT,
                        INLINE_FONT,
                        MATCH_CASE,
                        IMPORTANT,
                        ELEMHIDE,
                        GENERICHIDE,
                        GENERICBLOCK,
                        SPECIFICHIDE,
                        NOOP -> false;
            };
        }
    }

    private enum ValueRequirement {
        NONE,
        REQUIRED,
        OPTIONAL
    }

    record Result(Optional<String> content, String reason) {

        Result {
            Objects.requireNonNull(content, "content 不能为空");
            Objects.requireNonNull(reason, "reason 不能为空");
        }

        static Result success(String content) {
            return new Result(Optional.of(content), "");
        }

        static Result unsupported(String reason) {
            return new Result(Optional.empty(), reason);
        }
    }

    private record ModifierAlias(
            ModifierKind kind,
            Set<DialectProfile> sourceDialects,
            boolean invertNegation
    ) {
    }

    private record RedirectResource(String adguard, String ubo, String abp) {

        private String name(DialectProfile dialect) {
            return switch (dialect) {
                case ABP -> abp;
                case ADGUARD -> adguard;
                case UBO -> ubo;
                case ADBLOCK_BASE -> null;
            };
        }
    }

}

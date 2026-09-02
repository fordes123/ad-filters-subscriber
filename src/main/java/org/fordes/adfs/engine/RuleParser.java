package org.fordes.adfs.engine;

import org.fordes.adfs.ast.CommentAst;
import org.fordes.adfs.ast.CosmeticRuleAst;
import org.fordes.adfs.ast.EmptyAst;
import org.fordes.adfs.ast.ExtensionAst;
import org.fordes.adfs.ast.ExtensionKind;
import org.fordes.adfs.ast.HtmlFilterAst;
import org.fordes.adfs.ast.HtmlFilterSyntax;
import org.fordes.adfs.ast.MetadataAst;
import org.fordes.adfs.ast.NetworkAction;
import org.fordes.adfs.ast.NetworkAnchor;
import org.fordes.adfs.ast.NetworkModifierAst;
import org.fordes.adfs.ast.NetworkRuleAst;
import org.fordes.adfs.ast.PreprocessorDirectiveAst;
import org.fordes.adfs.ast.RuleAst;
import org.fordes.adfs.ast.ScriptletRuleAst;
import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.model.RuleBody;
import org.fordes.adfs.syntax.DecodeResult;
import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.AdblockDecoder;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class RuleParser {

    private static final Set<String> NON_FILTERABLE_HOST_SUFFIXES = Set.of(
            "alt",
            "arpa",
            "example",
            "example.com",
            "example.net",
            "example.org",
            "invalid",
            "local",
            "localdomain",
            "localhost",
            "onion",
            "test"
    );

    private final AdblockDecoder adblockDecoder;

    RuleParser() {
        this.adblockDecoder = new AdblockDecoder();
    }

    ParseOutcome parseAdblock(
            BuildPlan.SourceSpec source,
            LineSlice line
    ) {
        DecodeResult<RuleAst> result = adblockDecoder.decode(line, source.dialect());
        if (result instanceof DecodeResult.Invalid<RuleAst> invalid) {
            return ParseOutcome.invalid(
                    invalid.diagnostic().code(),
                    invalid.diagnostic().message()
            );
        }

        RuleAst ast = ((DecodeResult.Decoded<RuleAst>) result).ast();
        if (ast instanceof EmptyAst
                || ast instanceof CommentAst
                || ast instanceof MetadataAst
                || ast instanceof PreprocessorDirectiveAst) {
            return ParseOutcome.ignored();
        }
        if (ast instanceof NetworkRuleAst network) {
            return ParseOutcome.parsed(lowerNetwork(source, network));
        }
        return ParseOutcome.parsed(new RuleRecord(
                source.id(),
                source.profile(),
                ast.source().materialize(),
                new RuleBody.Extended(lowerExtended(ast))
        ));
    }

    ParseOutcome parseText(BuildPlan.SourceSpec source, String raw) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(raw, "raw 不能为空");
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return ParseOutcome.ignored();
        }
        return switch (source.format()) {
            case DNS -> parseAdguardDns(source, raw, line);
            case HOSTS -> parseHosts(source, raw, line);
            case DNSMASQ -> parseDnsmasq(source, raw, line);
            case SMARTDNS -> parseSmartDns(source, raw, line);
            case CLASH -> parseClash(source, raw, line);
            case EASYLIST -> throw new IllegalArgumentException("EASYLIST 必须使用 parseAdblock");
            case SING_BOX -> throw new IllegalArgumentException("SING_BOX 必须按 JSON 文档解析");
        };
    }

    static ParseOutcome parseSingBoxValue(
            BuildPlan.SourceSpec source,
            String field,
            String value,
            String raw
    ) {
        if (field.equals("ip_cidr")) {
            Optional<String> cidr = normalizeIpCidr(value);
            return cidr.<ParseOutcome>map(normalized -> canonical(source, raw, new CanonicalRule(
                    CanonicalRule.MatchType.IP_CIDR,
                    normalized,
                    CanonicalRule.Action.BLOCK,
                    Optional.empty(),
                    0
            ))).orElseGet(() -> ParseOutcome.invalid(
                    "INVALID_SING_BOX_IP_CIDR", "sing-box ip_cidr 无效: " + value));
        }
        if (field.equals("domain_regex")) {
            return canonical(source, raw, new CanonicalRule(
                    CanonicalRule.MatchType.DOMAIN_REGEX,
                    value,
                    CanonicalRule.Action.BLOCK,
                    Optional.empty(),
                    0
            ));
        }
        if (field.equals("domain_keyword")) {
            return canonical(source, raw, new CanonicalRule(
                    CanonicalRule.MatchType.DOMAIN_KEYWORD,
                    value,
                    CanonicalRule.Action.BLOCK,
                    Optional.empty(),
                    0
            ));
        }

        boolean subdomainsOnly = field.equals("domain_suffix") && value.startsWith(".");
        Optional<String> domain = normalizeDomain(value);
        if (domain.isEmpty()) {
            return ParseOutcome.invalid("INVALID_SING_BOX_DOMAIN", "sing-box " + field + " 包含无效域名");
        }
        CanonicalRule.MatchType matchType = field.equals("domain")
                ? CanonicalRule.MatchType.EXACT_DOMAIN
                : subdomainsOnly
                ? CanonicalRule.MatchType.SUBDOMAINS_ONLY
                : CanonicalRule.MatchType.DOMAIN_SUFFIX;
        return canonical(source, raw, new CanonicalRule(
                matchType,
                domain.orElseThrow(),
                CanonicalRule.Action.BLOCK,
                Optional.empty(),
                0
        ));
    }

    private ParseOutcome parseAdguardDns(
            BuildPlan.SourceSpec source,
            String raw,
            String line
    ) {
        List<String> fields = splitWhitespace(stripInlineComment(line));
        if (!fields.isEmpty() && isIpLiteral(fields.getFirst())) {
            return parseHosts(source, raw, line);
        }
        if (line.startsWith("!")) {
            return ParseOutcome.ignored();
        }
        return parseAdblock(source, LineSlice.fromUtf8(raw));
    }

    private static ParseOutcome parseHosts(
            BuildPlan.SourceSpec source,
            String raw,
            String line
    ) {
        String content = stripInlineComment(line);
        List<String> fields = splitWhitespace(content);
        if (fields.size() < 2 || !isIpLiteral(fields.getFirst())) {
            return ParseOutcome.invalid("INVALID_HOSTS_RULE", "hosts 规则必须是 <ip> <domain>");
        }
        String ip = fields.getFirst();
        CanonicalRule.Action action = isBlockingIp(ip)
                ? CanonicalRule.Action.BLOCK
                : CanonicalRule.Action.REWRITE;
        Optional<String> destination = action == CanonicalRule.Action.REWRITE
                ? Optional.of(ip)
                : Optional.empty();
        List<RuleRecord> rules = new ArrayList<>(fields.size() - 1);
        for (int index = 1; index < fields.size(); index++) {
            String hostnameValue = fields.get(index);
            if (isIpLiteral(hostnameValue) || !hostnameValue.contains(".")) {
                continue;
            }
            Optional<String> hostname = normalizeDomain(hostnameValue);
            if (hostname.isEmpty()) {
                return ParseOutcome.invalid(
                        "INVALID_DOMAIN",
                        "hosts 规则包含无效 hostname: " + hostnameValue
                );
            }
            if (isNonFilterableHostDomain(hostname.orElseThrow())) {
                continue;
            }
            rules.add(canonicalRecord(
                    source,
                    raw,
                    new CanonicalRule(
                            CanonicalRule.MatchType.EXACT_DOMAIN,
                            hostname.orElseThrow(),
                            action,
                            destination,
                            0
                    )
            ));
        }
        return rules.isEmpty() ? ParseOutcome.ignored() : ParseOutcome.parsed(rules);
    }

    private static ParseOutcome parseDnsmasq(
            BuildPlan.SourceSpec source,
            String raw,
            String line
    ) {
        String prefix = "address=/";
        if (!line.startsWith(prefix)) {
            return ParseOutcome.invalid("INVALID_DNSMASQ_RULE", "dnsmasq 规则必须以 address=/ 开头");
        }
        String body = line.substring(prefix.length());
        int separator = body.indexOf('/');
        if (separator < 1) {
            return ParseOutcome.invalid("INVALID_DNSMASQ_RULE", "dnsmasq 规则缺少域名结束符");
        }
        Optional<String> domain = normalizeDomain(body.substring(0, separator));
        if (domain.isEmpty()) {
            return ParseOutcome.invalid("INVALID_DOMAIN", "dnsmasq 规则包含无效域名");
        }
        String destinationValue = body.substring(separator + 1).trim();
        CanonicalRule.Action action = destinationValue.isEmpty() || isBlockingIp(destinationValue)
                ? CanonicalRule.Action.BLOCK
                : CanonicalRule.Action.REWRITE;
        if (action == CanonicalRule.Action.REWRITE && !isIpLiteral(destinationValue)) {
            return ParseOutcome.invalid("INVALID_IP", "dnsmasq rewrite 目标必须是 IP 地址");
        }
        return canonical(
                source,
                raw,
                new CanonicalRule(
                        CanonicalRule.MatchType.DOMAIN_SUFFIX,
                        domain.orElseThrow(),
                        action,
                        action == CanonicalRule.Action.REWRITE
                                ? Optional.of(destinationValue)
                                : Optional.empty(),
                        0
                )
        );
    }

    private static ParseOutcome parseSmartDns(
            BuildPlan.SourceSpec source,
            String raw,
            String line
    ) {
        String prefix = "address /";
        if (!line.startsWith(prefix)) {
            return ParseOutcome.invalid("INVALID_SMARTDNS_RULE", "smartdns 规则必须以 address / 开头");
        }
        String body = line.substring(prefix.length());
        int separator = body.lastIndexOf('/');
        if (separator < 1 || separator == body.length() - 1) {
            return ParseOutcome.invalid("INVALID_SMARTDNS_RULE", "smartdns 规则缺少控制符");
        }
        String domainText = body.substring(0, separator);
        CanonicalRule.MatchType matchType = CanonicalRule.MatchType.DOMAIN_SUFFIX;
        if (domainText.startsWith("-.")) {
            domainText = domainText.substring(2);
            matchType = CanonicalRule.MatchType.EXACT_DOMAIN;
        } else if (domainText.startsWith("*.")) {
            domainText = domainText.substring(2);
            matchType = CanonicalRule.MatchType.SUBDOMAINS_ONLY;
        } else if (domainText.startsWith(".")) {
            domainText = domainText.substring(1);
        }
        Optional<String> domain = normalizeDomain(domainText);
        if (domain.isEmpty()) {
            return ParseOutcome.invalid("INVALID_DOMAIN", "smartdns 规则包含无效域名");
        }
        CanonicalRule.Action action = switch (body.substring(separator + 1)) {
            case "#" -> CanonicalRule.Action.BLOCK;
            case "-" -> CanonicalRule.Action.ALLOW;
            default -> null;
        };
        if (action == null) {
            return ParseOutcome.invalid("UNSUPPORTED_SMARTDNS_CONTROL", "smartdns 仅支持 # 和 - 控制符");
        }
        return canonical(
                source,
                raw,
                new CanonicalRule(matchType, domain.orElseThrow(), action, Optional.empty(), 0)
        );
    }

    private static ParseOutcome parseClash(
            BuildPlan.SourceSpec source,
            String raw,
            String line
    ) {
        if (line.equals("payload:")) {
            return ParseOutcome.ignored();
        }
        String content = line.startsWith("-") ? line.substring(1).trim() : line;
        content = unquote(content);
        return switch (source.clashDialect()) {
            case DOMAIN -> parseClashDomain(source, raw, content);
            case IPCIDR -> parseClashIpCidr(source, raw, content);
            case CLASSICAL -> parseClashClassical(source, raw, content);
        };
    }

    private static ParseOutcome parseClashDomain(
            BuildPlan.SourceSpec source,
            String raw,
            String content
    ) {
        CanonicalRule.MatchType matchType = CanonicalRule.MatchType.EXACT_DOMAIN;
        if (content.startsWith("+.")) {
            content = content.substring(2);
            matchType = CanonicalRule.MatchType.DOMAIN_SUFFIX;
        } else if (content.startsWith("*.")) {
            content = content.substring(2);
            matchType = CanonicalRule.MatchType.SUBDOMAINS_ONLY;
        } else if (content.startsWith(".")) {
            content = content.substring(1);
            matchType = CanonicalRule.MatchType.SUBDOMAINS_ONLY;
        }
        Optional<String> domain = normalizeDomain(content);
        if (domain.isEmpty()) {
            return ParseOutcome.invalid("INVALID_CLASH_RULE", "Clash domain-set 规则包含无效域名");
        }
        return canonical(
                source,
                raw,
                new CanonicalRule(
                        matchType,
                        domain.orElseThrow(),
                        CanonicalRule.Action.BLOCK,
                        Optional.empty(),
                        0
                )
        );
    }

    private static ParseOutcome parseClashIpCidr(
            BuildPlan.SourceSpec source,
            String raw,
            String content
    ) {
        Optional<String> cidr = normalizeIpCidr(content);
        if (cidr.isEmpty()) {
            return ParseOutcome.invalid("INVALID_CLASH_IPCIDR_RULE", "Clash ipcidr 规则无效: " + content);
        }
        return canonical(source, raw, new CanonicalRule(
                CanonicalRule.MatchType.IP_CIDR,
                cidr.orElseThrow(),
                CanonicalRule.Action.BLOCK,
                Optional.empty(),
                0
        ));
    }

    private static ParseOutcome parseClashClassical(
            BuildPlan.SourceSpec source,
            String raw,
            String content
    ) {
        String[] fields = content.split(",", -1);
        if (fields.length < 2 || fields.length > 3
                || fields.length == 3 && !fields[2].trim().equalsIgnoreCase("no-resolve")) {
            return ParseOutcome.invalid(
                    "INVALID_CLASH_CLASSICAL_RULE",
                    "Clash classical 规则必须是 <type>,<value>[,no-resolve]"
            );
        }
        String type = fields[0].trim().toUpperCase(Locale.ROOT);
        String value = fields[1].trim();
        return switch (type) {
            case "DOMAIN" -> parseClashClassicalDomain(
                    source, raw, value, CanonicalRule.MatchType.EXACT_DOMAIN);
            case "DOMAIN-SUFFIX" -> parseClashClassicalDomain(
                    source, raw, value, CanonicalRule.MatchType.DOMAIN_SUFFIX);
            case "DOMAIN-KEYWORD" -> parseClashClassicalText(
                    source, raw, value, CanonicalRule.MatchType.DOMAIN_KEYWORD);
            case "DOMAIN-REGEX" -> parseClashClassicalText(
                    source, raw, value, CanonicalRule.MatchType.DOMAIN_REGEX);
            case "IP-CIDR", "IP-CIDR6" -> parseClashIpCidr(source, raw, value);
            case "RULE-SET", "SUB-RULE" -> ParseOutcome.invalid(
                    "UNSUPPORTED_CLASH_CLASSICAL_RULE",
                    "Mihomo classical rule-provider 不支持规则类型: " + type
            );
            default -> ParseOutcome.parsed(new RuleRecord(
                    source.id(),
                    source.profile(),
                    raw,
                    new RuleBody.Opaque(RuleRecord.SourceSyntax.CLASH_CLASSICAL.name)
            ));
        };
    }

    private static ParseOutcome parseClashClassicalDomain(
            BuildPlan.SourceSpec source,
            String raw,
            String value,
            CanonicalRule.MatchType matchType
    ) {
        Optional<String> domain = normalizeDomain(value);
        if (domain.isEmpty()) {
            return ParseOutcome.invalid("INVALID_CLASH_CLASSICAL_RULE", "Clash classical 规则包含无效域名");
        }
        return canonical(source, raw, new CanonicalRule(
                matchType,
                domain.orElseThrow(),
                CanonicalRule.Action.BLOCK,
                Optional.empty(),
                0
        ));
    }

    private static ParseOutcome parseClashClassicalText(
            BuildPlan.SourceSpec source,
            String raw,
            String value,
            CanonicalRule.MatchType matchType
    ) {
        if (value.isBlank()) {
            return ParseOutcome.invalid("INVALID_CLASH_CLASSICAL_RULE", "Clash classical 规则值不能为空");
        }
        return canonical(source, raw, new CanonicalRule(
                matchType,
                value,
                CanonicalRule.Action.BLOCK,
                Optional.empty(),
                0
        ));
    }

    private static RuleRecord lowerNetwork(BuildPlan.SourceSpec source, NetworkRuleAst ast) {
        String raw = ast.source().materialize();
        String pattern = ast.source().materialize(ast.pattern());

        int filterEnd = ast.modifierBlock()
                .map(Span::start)
                .map(start -> start - 1)
                .orElse(ast.source().length());
        String prefix = ast.source().materialize(
                new Span(0, ast.pattern().start()));
        int suffixStart = ast.pattern().end() + (ast.rightAnchor() ? 1 : 0);
        String suffix = ast.source().materialize(
                new Span(suffixStart, filterEnd));
        List<RuleBody.AdblockNetwork.Modifier> modifiers = ast.modifiers().stream()
                .map(modifier -> new RuleBody.AdblockNetwork.Modifier(
                        ast.source().materialize(modifier.source()),
                        ast.source().materialize(modifier.name()),
                        modifier.value().map(ast.source()::materialize),
                        modifier.negated()
                ))
                .toList();

        long featureMask = 0;
        boolean canonicalCompatible = true;
        for (NetworkModifierAst modifier : ast.modifiers()) {
            String name = ast.source().materialize(modifier.name()).toLowerCase(Locale.ROOT);
            if (modifier.negated() || modifier.value().isPresent()) {
                canonicalCompatible = false;
                continue;
            }
            if (name.equals("important")) {
                featureMask |= CanonicalRule.FEATURE_IMPORTANT;
            } else if (name.equals("all")) {
                featureMask |= CanonicalRule.FEATURE_ALL;
            } else {
                canonicalCompatible = false;
            }
        }

        CanonicalRule.Action action = ast.action() == NetworkAction.ALLOW
                ? CanonicalRule.Action.ALLOW
                : CanonicalRule.Action.BLOCK;
        Optional<CanonicalRule> canonical = Optional.empty();
        if (canonicalCompatible && ast.regex()) {
            canonical = Optional.of(new CanonicalRule(
                    CanonicalRule.MatchType.REGEX,
                    pattern,
                    action,
                    Optional.empty(),
                    featureMask
            ));
        } else if (canonicalCompatible) {
            boolean qualified = pattern.endsWith("^");
            String target = qualified ? pattern.substring(0, pattern.length() - 1) : pattern;
            Optional<String> domain = normalizeDomain(target);
            if (ast.leftAnchor() == NetworkAnchor.DOMAIN && qualified && domain.isPresent()) {
                canonical = Optional.of(new CanonicalRule(
                        CanonicalRule.MatchType.DOMAIN_SUFFIX,
                        domain.orElseThrow(),
                        action,
                        Optional.empty(),
                        featureMask
                ));
            } else if (ast.leftAnchor() == NetworkAnchor.NONE
                    && !ast.rightAnchor()
                    && !qualified
                    && domain.isPresent()
                    && source.format() == RuleFormat.DNS) {
                canonical = Optional.of(new CanonicalRule(
                        CanonicalRule.MatchType.EXACT_DOMAIN,
                        domain.orElseThrow(),
                        action,
                        Optional.empty(),
                        featureMask
                ));
            } else if (ast.leftAnchor() == NetworkAnchor.NONE
                    && !ast.rightAnchor()
                    && !qualified
                    && domain.isPresent()
                    && source.dialect() == DialectProfile.UBO) {
                canonical = Optional.of(new CanonicalRule(
                        CanonicalRule.MatchType.DOMAIN_SUFFIX,
                        domain.orElseThrow(),
                        action,
                        Optional.empty(),
                        featureMask
                ));
            } else {
                canonical = Optional.of(new CanonicalRule(
                        CanonicalRule.MatchType.URL_PATTERN,
                        pattern,
                        action,
                        Optional.empty(),
                        featureMask
                ));
            }
        }
        return new RuleRecord(
                source.id(),
                source.profile(),
                raw,
                new RuleBody.AdblockNetwork(
                        ast.action(),
                        ast.leftAnchor(),
                        ast.rightAnchor(),
                        ast.regex(),
                        prefix,
                        pattern,
                        suffix,
                        modifiers,
                        canonical
                )
        );
    }

    private static ParseOutcome canonical(
            BuildPlan.SourceSpec source,
            String raw,
            CanonicalRule rule
    ) {
        return ParseOutcome.parsed(canonicalRecord(source, raw, rule));
    }

    private static RuleRecord canonicalRecord(
            BuildPlan.SourceSpec source,
            String raw,
            CanonicalRule rule
    ) {
        return new RuleRecord(
                source.id(),
                source.profile(),
                raw,
                new RuleBody.Canonical(rule)
        );
    }

    private static RuleRecord unknown(BuildPlan.SourceSpec source, String raw) {
        return new RuleRecord(
                source.id(),
                source.profile(),
                raw,
                new RuleBody.Opaque(RuleRecord.SourceSyntax.NETWORK.name)
        );
    }

    static Optional<String> normalizeDomain(String value) {
        return normalizeHostname(value, false);
    }

    private static Optional<String> normalizeHostname(String value, boolean allowSingleLabel) {
        String domain = value.trim();
        while (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty() || domain.length() > 253 || domain.contains("/")) {
            return Optional.empty();
        }
        try {
            String ascii = isAscii(domain)
                    ? domain.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253) {
                return Optional.empty();
            }
            int labels = 0;
            int labelStart = 0;
            for (int index = 0; index <= ascii.length(); index++) {
                if (index < ascii.length() && ascii.charAt(index) != '.') {
                    continue;
                }
                int labelLength = index - labelStart;
                if (labelLength < 1 || labelLength > 63
                        || ascii.charAt(labelStart) == '-'
                        || ascii.charAt(index - 1) == '-') {
                    return Optional.empty();
                }
                for (int cursor = labelStart; cursor < index; cursor++) {
                    char character = ascii.charAt(cursor);
                    if (!isAsciiLetterOrDigit(character) && character != '-') {
                        return Optional.empty();
                    }
                }
                labels++;
                labelStart = index + 1;
            }
            if (!allowSingleLabel && labels < 2) {
                return Optional.empty();
            }
            return Optional.of(ascii);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private static AdblockExtendedRule lowerExtended(RuleAst ast) {
        return switch (ast) {
            case CosmeticRuleAst cosmetic -> extended(
                    cosmetic.action(),
                    switch (cosmetic.syntax()) {
                        case ELEMENT_HIDING -> AdblockExtendedRule.Syntax.COSMETIC;
                        case EXTENDED_SELECTOR, EXTENDED_CSS ->
                                AdblockExtendedRule.Syntax.ADGUARD_EXTENDED_COSMETIC;
                        case CSS_INJECTION -> AdblockExtendedRule.Syntax.CSS_INJECTION;
                    },
                    cosmetic.source(),
                    cosmetic.nonBasicModifiers(),
                    cosmetic.domains(),
                    cosmetic.body(),
                    Optional.empty()
            );
            case ScriptletRuleAst scriptlet -> {
                AdblockExtendedRule.Syntax syntax = switch (scriptlet.syntax()) {
                    case UBO_SCRIPTLET -> AdblockExtendedRule.Syntax.UBO_SCRIPTLET;
                    case ADGUARD_SCRIPTLET -> AdblockExtendedRule.Syntax.ADGUARD_SCRIPTLET;
                    case ABP_SNIPPET -> AdblockExtendedRule.Syntax.ABP_SNIPPET;
                };
                String body = scriptlet.source().materialize(scriptlet.body());
                yield extended(
                        scriptlet.action(),
                        syntax,
                        scriptlet.source(),
                        scriptlet.nonBasicModifiers(),
                        scriptlet.domains(),
                        scriptlet.body(),
                        scriptletName(syntax, body)
                );
            }
            case HtmlFilterAst html -> extended(
                    html.action(),
                    html.syntax() == HtmlFilterSyntax.UBO
                            ? AdblockExtendedRule.Syntax.UBO_HTML
                            : AdblockExtendedRule.Syntax.ADGUARD_HTML,
                    html.source(),
                    html.nonBasicModifiers(),
                    html.domains(),
                    html.body(),
                    Optional.empty()
            );
            case ExtensionAst extension -> extended(
                    extension.action(),
                    extension.kind() == ExtensionKind.ADGUARD_JAVASCRIPT
                            ? AdblockExtendedRule.Syntax.ADGUARD_JAVASCRIPT
                            : AdblockExtendedRule.Syntax.DIALECT_SPECIFIC_EXTENSION,
                    extension.source(),
                    extension.nonBasicModifiers(),
                    extension.domains(),
                    extension.body(),
                    Optional.empty()
            );
            default -> throw new IllegalArgumentException(
                    "不支持转换为扩展规则的 AST: " + ast.getClass().getSimpleName());
        };
    }

    private static AdblockExtendedRule extended(
            org.fordes.adfs.ast.ExtendedAction action,
            AdblockExtendedRule.Syntax syntax,
            LineSlice source,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span body,
            Optional<String> scriptletName
    ) {
        return new AdblockExtendedRule(
                syntax,
                action == org.fordes.adfs.ast.ExtendedAction.APPLY
                        ? AdblockExtendedRule.Action.APPLY
                        : AdblockExtendedRule.Action.EXCEPT,
                nonBasicModifiers.map(source::materialize),
                source.materialize(domains),
                source.materialize(body),
                scriptletName
        );
    }

    private static Optional<String> scriptletName(
            AdblockExtendedRule.Syntax syntax,
            String body
    ) {
        int cursor;
        if (syntax == AdblockExtendedRule.Syntax.UBO_SCRIPTLET && body.startsWith("+js(")) {
            cursor = 4;
        } else if (syntax == AdblockExtendedRule.Syntax.ADGUARD_SCRIPTLET
                && body.startsWith("//scriptlet(")) {
            cursor = 12;
        } else {
            return Optional.empty();
        }
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
            cursor++;
        }
        char quote = cursor < body.length() && (body.charAt(cursor) == '\'' || body.charAt(cursor) == '"')
                ? body.charAt(cursor++)
                : 0;
        int start = cursor;
        while (cursor < body.length()) {
            char character = body.charAt(cursor);
            if (quote != 0 ? character == quote : character == ',' || character == ')') {
                break;
            }
            cursor++;
        }
        String name = body.substring(start, cursor).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    private static boolean isIpLiteral(String value) {
        return parseIpLiteral(value).isPresent();
    }

    private static Optional<String> normalizeIpCidr(String value) {
        String cidr = value.trim();
        int separator = cidr.lastIndexOf('/');
        if (separator < 1 || separator == cidr.length() - 1) {
            return Optional.empty();
        }
        Optional<InetAddress> address = parseIpLiteral(cidr.substring(0, separator));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(separator + 1));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
        InetAddress parsedAddress = address.orElseThrow();
        int maxPrefix = parsedAddress.getAddress().length * 8;
        if (prefix < 0 || prefix > maxPrefix) {
            return Optional.empty();
        }
        return Optional.of(parsedAddress.getHostAddress() + "/" + prefix);
    }

    private static Optional<InetAddress> parseIpLiteral(String value) {
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            byte[] address = new byte[4];
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (part.isEmpty() || part.length() > 3
                        || part.chars().anyMatch(character -> character < '0' || character > '9')) {
                    return Optional.empty();
                }
                int number = Integer.parseInt(part);
                if (number > 255) {
                    return Optional.empty();
                }
                address[index] = (byte) number;
            }
            try {
                return Optional.of(InetAddress.getByAddress(address));
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException("IPv4 固定长度地址解析失败", impossible);
            }
        }
        String addressValue = value;
        int zoneSeparator = value.lastIndexOf('%');
        if (zoneSeparator >= 0) {
            if (zoneSeparator == 0
                    || zoneSeparator == value.length() - 1
                    || !validIpv6Zone(value.substring(zoneSeparator + 1))) {
                return Optional.empty();
            }
            addressValue = value.substring(0, zoneSeparator);
        }
        try {
            InetAddress address = InetAddress.getByName(addressValue);
            return address.getAddress().length == 16 ? Optional.of(address) : Optional.empty();
        } catch (UnknownHostException error) {
            return Optional.empty();
        }
    }

    private static boolean validIpv6Zone(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockingIp(String value) {
        Optional<InetAddress> address = parseIpLiteral(value);
        if (address.isEmpty()) {
            return false;
        }
        InetAddress parsedAddress = address.orElseThrow();
        return parsedAddress.isAnyLocalAddress() || parsedAddress.isLoopbackAddress();
    }

    private static boolean isNonFilterableHostDomain(String hostname) {
        return NON_FILTERABLE_HOST_SUFFIXES.stream().anyMatch(suffix ->
                hostname.equals(suffix) || hostname.endsWith("." + suffix));
    }

    private static String stripInlineComment(String value) {
        int comment = value.indexOf('#');
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    private static List<String> splitWhitespace(String value) {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            while (cursor < value.length() && !Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            if (start < cursor) {
                result.add(value.substring(start, cursor));
            }
        }
        return List.copyOf(result);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    public sealed interface ParseOutcome permits Parsed, Invalid, Ignored {

        default List<RuleRecord> rules() {
            return this instanceof Parsed parsed ? parsed.values() : List.of();
        }

        default Optional<ParseIssue> issue() {
            return this instanceof Invalid invalid
                    ? Optional.of(invalid.value())
                    : Optional.empty();
        }

        static ParseOutcome parsed(RuleRecord rule) {
            return new Parsed(List.of(rule));
        }

        static ParseOutcome parsed(List<RuleRecord> rules) {
            return new Parsed(rules);
        }

        static ParseOutcome invalid(String code, String message) {
            return new Invalid(new ParseIssue(code, message));
        }

        static ParseOutcome ignored() {
            return Ignored.INSTANCE;
        }

    }

    public record Parsed(List<RuleRecord> values) implements ParseOutcome {

        public Parsed {
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("已解析规则不能为空");
            }
        }
    }

    public record Invalid(ParseIssue value) implements ParseOutcome {

        public Invalid {
            Objects.requireNonNull(value, "value 不能为空");
        }
    }

    public enum Ignored implements ParseOutcome {
        INSTANCE
    }

    public record ParseIssue(String code, String message) {

        public ParseIssue {
            Objects.requireNonNull(code, "code 不能为空");
            Objects.requireNonNull(message, "message 不能为空");
            if (code.isBlank() || message.isBlank()) {
                throw new IllegalArgumentException("parse issue code/message 不能为空");
            }
        }
    }
}

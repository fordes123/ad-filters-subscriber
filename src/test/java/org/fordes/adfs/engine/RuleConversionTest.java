package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuleConversionTest {

    private final RuleParser parser = new RuleParser();
    private final RuleEncoder encoder = new RuleEncoder();

    @Test
    void convertsDnsmasqSuffixToMihomoAndHosts() {
        RuleRecord rule = parse(
                source("dnsmasq", RuleFormat.DNSMASQ),
                "address=/ads.example.com/"
        );

        assertEquals(
                "  - 'DOMAIN-SUFFIX,ads.example.com'",
                encode(rule, RuleFormat.CLASH, false).content().orElseThrow()
        );
        assertEquals(
                RuleEncoder.ConversionStatus.UNSUPPORTED,
                encode(rule, RuleFormat.HOSTS, false).status()
        );
        assertEquals(
                "0.0.0.0\tads.example.com",
                encode(rule, RuleFormat.HOSTS, true).content().orElseThrow()
        );
    }

    @Test
    void preservesSmartDnsAllowSemanticsWhenConvertingToAdblock() {
        RuleRecord rule = parse(
                source("smartdns", RuleFormat.SMARTDNS),
                "address /allow.example.com/-"
        );

        assertEquals(
                "@@||allow.example.com^",
                encode(rule, RuleFormat.EASYLIST, false).content().orElseThrow()
        );
    }

    @Test
    void parsesMihomoDomainSuffix() {
        RuleRecord rule = parse(
                new BuildPlan.SourceSpec(
                        "clash",
                        "unused",
                        RuleFormat.CLASH,
                        DialectProfile.ADBLOCK_BASE,
                        ClashDialect.DOMAIN,
                        0
                ),
                "  - '+.cdn.example.com'"
        );

        assertEquals(
                "||cdn.example.com^",
                encode(rule, RuleFormat.EASYLIST, false).content().orElseThrow()
        );
    }

    @Test
    void parsesAdguardDnsHostsRewrite() {
        RuleRecord rule = parse(
                source("adguard", RuleFormat.DNS),
                "1.1.1.1 resolver.test-domain.com"
        );

        RuleEncoder.ConversionResult hosts = encode(rule, RuleFormat.HOSTS, false);
        assertTrue(hosts.content().isPresent());
        assertEquals("1.1.1.1\tresolver.test-domain.com", hosts.content().orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("parserCases")
    void parsesEveryTextFormat(
            BuildPlan.SourceSpec source,
            String text,
            CanonicalRule.MatchType matchType,
            CanonicalRule.Action action,
            String value
    ) {
        CanonicalRule rule = parse(source, text).canonical().orElseThrow();

        assertEquals(matchType, rule.matchType());
        assertEquals(action, rule.action());
        assertEquals(value, rule.value());
    }

    @Test
    void rejectsMalformedRulesInsteadOfSilentlyIgnoringThem() {
        assertIssue(source("hosts", RuleFormat.HOSTS), "not-a-hosts-rule", "INVALID_HOSTS_RULE");
        assertIssue(source("dnsmasq", RuleFormat.DNSMASQ), "server=/example.com/", "INVALID_DNSMASQ_RULE");
        assertIssue(source("smartdns", RuleFormat.SMARTDNS), "address /example.com/x", "UNSUPPORTED_SMARTDNS_CONTROL");
        assertIssue(clashSource(ClashDialect.IPCIDR), "300.1.1.1/24", "INVALID_CLASH_IPCIDR_RULE");
        assertIssue(clashSource(ClashDialect.CLASSICAL), "RULE-SET,remote", "UNSUPPORTED_CLASH_CLASSICAL_RULE");
        assertIssue(clashSource(ClashDialect.DOMAIN), "payload: unexpected", "INVALID_CLASH_RULE");
    }

    @Test
    void appliesNarrowingAndBroadeningPolicies() {
        RuleRecord exact = canonical(CanonicalRule.MatchType.EXACT_DOMAIN, CanonicalRule.Action.BLOCK);
        RuleRecord suffix = canonical(CanonicalRule.MatchType.DOMAIN_SUFFIX, CanonicalRule.Action.BLOCK);

        assertEquals(RuleEncoder.ConversionStatus.UNSUPPORTED,
                encode(exact, RuleFormat.DNSMASQ, false, false, ClashDialect.CLASSICAL).status());
        assertEquals(RuleEncoder.ConversionStatus.BROADENING,
                encode(exact, RuleFormat.DNSMASQ, false, true, ClashDialect.CLASSICAL).status());
        assertEquals(RuleEncoder.ConversionStatus.UNSUPPORTED,
                encode(suffix, RuleFormat.HOSTS, false, false, ClashDialect.CLASSICAL).status());
        assertEquals(RuleEncoder.ConversionStatus.NARROWING,
                encode(suffix, RuleFormat.HOSTS, true, false, ClashDialect.CLASSICAL).status());
    }

    @ParameterizedTest
    @MethodSource("encoderCases")
    void encodesSupportedCanonicalRules(
            CanonicalRule.MatchType matchType,
            RuleFormat format,
            ClashDialect clashDialect,
            String expected
    ) {
        RuleEncoder.ConversionResult result = encode(
                canonical(matchType, CanonicalRule.Action.BLOCK),
                format,
                true,
                true,
                clashDialect
        );

        assertTrue(result.content().isPresent(), result::reason);
        assertEquals(expected, result.content().orElseThrow());
    }

    @Test
    void convertsCommonExtendedRulesAcrossCompatibleDialects() {
        RuleRecord cosmetic = parseAdblock(
                easylistSource("source", DialectProfile.UBO),
                "example.com##.ad"
        );

        RuleEncoder.ConversionResult compatible = new RuleEncoder().encode(
                cosmetic,
                output(RuleFormat.EASYLIST, DialectProfile.UBO, ClashDialect.CLASSICAL),
                true,
                true
        );
        assertEquals("example.com##.ad", compatible.content().orElseThrow());
        RuleEncoder.ConversionResult incompatible = new RuleEncoder().encode(
                cosmetic,
                output(RuleFormat.EASYLIST, DialectProfile.ABP, ClashDialect.CLASSICAL),
                true,
                true
        );
        assertEquals("example.com##.ad", incompatible.content().orElseThrow());
    }

    @Test
    void convertsCompatibleUboScriptletAndHtmlRulesToAdguard() {
        BuildPlan.SourceSpec mixedAdguardSource = easylistSource(
                "mixed",
                DialectProfile.ADGUARD
        );
        RuleRecord scriptlet = parseAdblock(
                mixedAdguardSource,
                "example.com##+js(json-prune, ad)"
        );
        RuleRecord html = parseAdblock(
                mixedAdguardSource,
                "example.com##^script:has-text(ad)"
        );

        RuleEncoder.ConversionResult scriptletResult = encodeAdblock(
                scriptlet,
                DialectProfile.ADGUARD
        );
        RuleEncoder.ConversionResult htmlResult = encodeAdblock(
                html,
                DialectProfile.ADGUARD
        );

        assertEquals("example.com##+js(json-prune, ad)",
                scriptletResult.content().orElseThrow());
        assertEquals("example.com$$script:has-text(ad)",
                htmlResult.content().orElseThrow());
    }

    @Test
    void reportsFriendlyTypedFailuresForUnsupportedExtendedRules() {
        RuleRecord unsupportedScriptlet = parseAdblock(
                easylistSource("ubo", DialectProfile.UBO),
                "example.com##+js(rpnt, script, ad, replacement)"
        );
        RuleRecord html = parseAdblock(
                easylistSource("ubo", DialectProfile.UBO),
                "example.com##^script:has-text(ad)"
        );

        RuleEncoder.ConversionResult scriptletResult = encodeAdblock(
                unsupportedScriptlet,
                DialectProfile.ADGUARD
        );
        RuleEncoder.ConversionResult hostsResult = encoder.encode(
                html,
                output(RuleFormat.HOSTS, DialectProfile.ADBLOCK_BASE, ClashDialect.CLASSICAL),
                true,
                true
        );

        assertEquals(RuleEncoder.ConversionFailure.SCRIPTLET_UNSUPPORTED,
                scriptletResult.failure().orElseThrow());
        assertEquals("AdGuard 尚未确认兼容 uBO scriptlet “rpnt”", scriptletResult.reason());
        assertFalse(scriptletResult.reason().contains("SCRIPTLET_UNSUPPORTED"));
        assertEquals(RuleEncoder.ConversionFailure.TARGET_FORMAT_UNSUPPORTED,
                hostsResult.failure().orElseThrow());
        assertEquals("hosts 只能表达网络或域名匹配规则，无法表达页面级扩展规则",
                hostsResult.reason());
    }

    @Test
    void convertsPortableAdguardHtmlRuleToUbo() {
        RuleRecord html = parseAdblock(
                easylistSource("adguard", DialectProfile.ADGUARD),
                "example.com$$script:has-text(ad)"
        );

        RuleEncoder.ConversionResult result = encodeAdblock(html, DialectProfile.UBO);

        assertEquals("example.com##^script:has-text(ad)", result.content().orElseThrow());
    }

    private RuleRecord parse(BuildPlan.SourceSpec source, String line) {
        RuleParser.ParseOutcome outcome = parser.parseText(source, line);
        assertTrue(outcome.issue().isEmpty(), () -> outcome.issue().toString());
        return outcome.rules().getFirst();
    }

    private RuleRecord parseAdblock(BuildPlan.SourceSpec source, String line) {
        RuleParser.ParseOutcome outcome = parser.parseAdblock(source, LineSlice.fromUtf8(line));
        assertTrue(outcome.issue().isEmpty(), () -> outcome.issue().toString());
        return outcome.rules().getFirst();
    }

    private RuleEncoder.ConversionResult encodeAdblock(
            RuleRecord rule,
            DialectProfile dialect
    ) {
        return encoder.encode(
                rule,
                output(RuleFormat.EASYLIST, dialect, ClashDialect.CLASSICAL),
                true,
                true
        );
    }

    private RuleEncoder.ConversionResult encode(
            RuleRecord rule,
            RuleFormat format,
            boolean allowNarrowing
    ) {
        return encoder.encode(
                rule,
                output(format, defaultDialect(format), ClashDialect.CLASSICAL),
                allowNarrowing,
                false
        );
    }

    private RuleEncoder.ConversionResult encode(
            RuleRecord rule,
            RuleFormat format,
            boolean allowNarrowing,
            boolean allowBroadening,
            ClashDialect clashDialect
    ) {
        return encoder.encode(
                rule,
                output(format, defaultDialect(format), clashDialect),
                allowNarrowing,
                allowBroadening
        );
    }

    private void assertIssue(BuildPlan.SourceSpec source, String line, String code) {
        RuleParser.ParseOutcome outcome = parser.parseText(source, line);
        assertEquals(code, outcome.issue().orElseThrow().code());
    }

    private static RuleRecord canonical(
            CanonicalRule.MatchType matchType,
            CanonicalRule.Action action
    ) {
        return new RuleRecord(
                "canonical",
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                "fixture",
                Optional.of(new CanonicalRule(
                        matchType,
                        matchType == CanonicalRule.MatchType.IP_CIDR ? "10.0.0.0/8" : "example.com",
                        action,
                        Optional.empty(),
                        0
                )),
                RuleRecord.SourceSyntax.CANONICAL
        );
    }

    private static BuildPlan.OutputSpec output(
            RuleFormat format,
            DialectProfile dialect,
            ClashDialect clashDialect
    ) {
        return new BuildPlan.OutputSpec(
                Path.of("target", "conversion-" + format.name().toLowerCase()),
                format,
                dialect,
                clashDialect,
                "",
                "",
                Set.of()
        );
    }

    private static Stream<Arguments> parserCases() {
        return Stream.of(
                Arguments.of(source("hosts", RuleFormat.HOSTS), "0.0.0.0 ads.test-domain.com",
                        CanonicalRule.MatchType.EXACT_DOMAIN, CanonicalRule.Action.BLOCK, "ads.test-domain.com"),
                Arguments.of(source("dnsmasq", RuleFormat.DNSMASQ), "address=/ads.example.com/1.1.1.1",
                        CanonicalRule.MatchType.DOMAIN_SUFFIX, CanonicalRule.Action.REWRITE, "ads.example.com"),
                Arguments.of(source("smartdns", RuleFormat.SMARTDNS), "address /*.ads.example.com/#",
                        CanonicalRule.MatchType.SUBDOMAINS_ONLY, CanonicalRule.Action.BLOCK, "ads.example.com"),
                Arguments.of(clashSource(ClashDialect.DOMAIN), "  - '+.ads.example.com'",
                        CanonicalRule.MatchType.DOMAIN_SUFFIX, CanonicalRule.Action.BLOCK, "ads.example.com"),
                Arguments.of(clashSource(ClashDialect.IPCIDR), "10.0.0.1/8",
                        CanonicalRule.MatchType.IP_CIDR, CanonicalRule.Action.BLOCK, "10.0.0.1/8"),
                Arguments.of(clashSource(ClashDialect.CLASSICAL), "DOMAIN-KEYWORD,advert",
                        CanonicalRule.MatchType.DOMAIN_KEYWORD, CanonicalRule.Action.BLOCK, "advert"),
                Arguments.of(source("dns", RuleFormat.DNS), "||ads.example.com^",
                        CanonicalRule.MatchType.DOMAIN_SUFFIX, CanonicalRule.Action.BLOCK, "ads.example.com")
        );
    }

    private static Stream<Arguments> encoderCases() {
        return Stream.of(
                Arguments.of(CanonicalRule.MatchType.DOMAIN_SUFFIX, RuleFormat.EASYLIST,
                        ClashDialect.CLASSICAL, "||example.com^"),
                Arguments.of(CanonicalRule.MatchType.EXACT_DOMAIN, RuleFormat.DNS,
                        ClashDialect.CLASSICAL, "example.com"),
                Arguments.of(CanonicalRule.MatchType.EXACT_DOMAIN, RuleFormat.HOSTS,
                        ClashDialect.CLASSICAL, "0.0.0.0\texample.com"),
                Arguments.of(CanonicalRule.MatchType.DOMAIN_SUFFIX, RuleFormat.DNSMASQ,
                        ClashDialect.CLASSICAL, "address=/example.com/"),
                Arguments.of(CanonicalRule.MatchType.SUBDOMAINS_ONLY, RuleFormat.SMARTDNS,
                        ClashDialect.CLASSICAL, "address /*.example.com/#"),
                Arguments.of(CanonicalRule.MatchType.IP_CIDR, RuleFormat.CLASH,
                        ClashDialect.IPCIDR, "  - '10.0.0.0/8'"),
                Arguments.of(CanonicalRule.MatchType.DOMAIN_KEYWORD, RuleFormat.SING_BOX,
                        ClashDialect.CLASSICAL, "    {\"domain_keyword\":[\"example.com\"]}")
        );
    }

    private static BuildPlan.SourceSpec clashSource(ClashDialect dialect) {
        return new BuildPlan.SourceSpec(
                "clash",
                "unused",
                RuleFormat.CLASH,
                DialectProfile.ADBLOCK_BASE,
                dialect,
                0
        );
    }

    private static BuildPlan.SourceSpec easylistSource(
            String id,
            DialectProfile dialect
    ) {
        return new BuildPlan.SourceSpec(
                id,
                "unused",
                RuleFormat.EASYLIST,
                dialect,
                ClashDialect.CLASSICAL,
                0
        );
    }

    private static BuildPlan.SourceSpec source(String id, RuleFormat format) {
        return new BuildPlan.SourceSpec(
                id,
                "unused",
                format,
                defaultDialect(format),
                ClashDialect.CLASSICAL,
                0
        );
    }

    private static DialectProfile defaultDialect(RuleFormat format) {
        return switch (format) {
            case EASYLIST -> DialectProfile.ABP;
            case DNS -> DialectProfile.ADGUARD;
            case HOSTS, DNSMASQ, SMARTDNS, CLASH, SING_BOX -> DialectProfile.ADBLOCK_BASE;
        };
    }
}

package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleBody;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SingBoxStreamingDecoderTest {

    private final SingBoxStreamingDecoder decoder = new SingBoxStreamingDecoder();

    @Test
    void decodesAllSupportedFieldsAndArrayValues() throws Exception {
        String json = """
                {
                  "version": 2,
                  "rules": [
                    {"domain": ["exact.example.com", "second.example.com"]},
                    {"domain_suffix": ".children.example.com"},
                    {"domain_keyword": "advert"},
                    {"domain_regex": "^ad[0-9]+\\\\.example$"},
                    {"ip_cidr": ["10.0.0.0/8", "2001:db8::/32"]}
                  ],
                  "ignored": {"nested": true}
                }
                """;
        List<RuleRecord> rules = new ArrayList<>();

        SingBoxStreamingDecoder.Result result = decode(json, rules);

        assertTrue(result.issue().isEmpty());
        assertEquals(7, result.emitted());
        assertEquals(List.of(
                        CanonicalRule.MatchType.EXACT_DOMAIN,
                        CanonicalRule.MatchType.EXACT_DOMAIN,
                        CanonicalRule.MatchType.SUBDOMAINS_ONLY,
                        CanonicalRule.MatchType.DOMAIN_KEYWORD,
                        CanonicalRule.MatchType.DOMAIN_REGEX,
                        CanonicalRule.MatchType.IP_CIDR,
                        CanonicalRule.MatchType.IP_CIDR
                ),
                rules.stream()
                        .map(rule -> ((RuleBody.Canonical) rule.body()).value().matchType())
                        .toList());
    }

    @Test
    void reportsDocumentAndRuleStructureErrors() throws Exception {
        assertIssue("[]", "INVALID_SING_BOX_RULE_SET");
        assertIssue("{\"rules\":[]}", "INVALID_SING_BOX_VERSION");
        assertIssue("{\"version\":0,\"rules\":[]}", "INVALID_SING_BOX_VERSION");
        assertIssue("{\"version\":2}", "INVALID_SING_BOX_RULES");
        assertIssue("{\"version\":2,\"rules\":{}}", "INVALID_SING_BOX_RULES");
        assertIssue("{\"version\":2,\"rules\":[{}]}", "UNSUPPORTED_SING_BOX_RULE");
        assertIssue("{\"version\":2,\"rules\":[{\"port\":53}]}", "UNSUPPORTED_SING_BOX_RULE");
        assertIssue("{\"version\":2,\"rules\":[{\"domain\":\"a.com\",\"ip_cidr\":\"1.1.1.1/32\"}]}",
                "UNSUPPORTED_SING_BOX_RULE");
        assertIssue("{\"version\":2,\"rules\":[]} true", "TRAILING_SING_BOX_CONTENT");
    }

    private SingBoxStreamingDecoder.Result decode(String json, List<RuleRecord> rules) throws Exception {
        return decoder.decode(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8,
                source(),
                rules::add
        );
    }

    private void assertIssue(String json, String code) throws Exception {
        SingBoxStreamingDecoder.Result result = decode(json, new ArrayList<>());
        assertEquals(code, result.issue().orElseThrow().code());
    }

    private static BuildPlan.SourceSpec source() {
        return new BuildPlan.SourceSpec(
                "sing-box",
                "unused",
                RuleFormat.SING_BOX,
                DialectProfile.ADBLOCK_BASE,
                ClashDialect.CLASSICAL,
                0
        );
    }
}

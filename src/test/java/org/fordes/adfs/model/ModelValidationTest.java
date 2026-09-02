package org.fordes.adfs.model;

import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ModelValidationTest {

    @Test
    void enforcesCanonicalRewriteDestinationInvariant() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalRule(
                CanonicalRule.MatchType.EXACT_DOMAIN,
                "example.com",
                CanonicalRule.Action.REWRITE,
                Optional.empty(),
                0
        ));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalRule(
                CanonicalRule.MatchType.EXACT_DOMAIN,
                "example.com",
                CanonicalRule.Action.BLOCK,
                Optional.of("1.1.1.1"),
                0
        ));

        CanonicalRule rewrite = new CanonicalRule(
                CanonicalRule.MatchType.EXACT_DOMAIN,
                "example.com",
                CanonicalRule.Action.REWRITE,
                Optional.of("1.1.1.1"),
                0
        );
        assertEquals("1.1.1.1", rewrite.destination().orElseThrow());
    }

    @Test
    void requiresRuleSourceAndRawText() {
        assertThrows(IllegalArgumentException.class, () -> new RuleRecord(
                " ",
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                "rule",
                new RuleBody.Opaque(RuleRecord.SourceSyntax.OPAQUE.name),
                RuleRecord.SourceSyntax.OPAQUE
        ));
        assertThrows(IllegalArgumentException.class, () -> new RuleRecord(
                "source",
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                " ",
                new RuleBody.Opaque(RuleRecord.SourceSyntax.OPAQUE.name),
                RuleRecord.SourceSyntax.OPAQUE
        ));
    }

    @Test
    void parsesRuleFormatsAndClashDialectsCaseInsensitively() {
        assertEquals(RuleFormat.SING_BOX, RuleFormat.parse(" sing-box "));
        assertEquals(ClashDialect.IPCIDR, ClashDialect.parse(" ipcidr "));
        assertThrows(IllegalArgumentException.class, () -> RuleFormat.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> ClashDialect.parse("unknown"));
    }
}

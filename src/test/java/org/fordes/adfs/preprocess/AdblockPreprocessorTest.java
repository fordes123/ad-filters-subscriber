package org.fordes.adfs.preprocess;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdblockPreprocessorTest {

    @Test
    void acceptsBalancedConditionalDirectives() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.UBO);

        assertTrue(preprocessor.process(LineSlice.fromUtf8("!#if env_chromium"), 1)
                .diagnostics().isEmpty());
        assertTrue(preprocessor.process(LineSlice.fromUtf8("!#else"), 2)
                .diagnostics().isEmpty());
        assertTrue(preprocessor.process(LineSlice.fromUtf8("!#endif"), 3)
                .diagnostics().isEmpty());
        assertTrue(preprocessor.finish().isEmpty());
    }

    @Test
    void selectsOnlyTheActiveConditionalBranch() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.ADGUARD);

        preprocessor.process(LineSlice.fromUtf8("!#if (adguard && !ext_ublock)"), 1);
        PreprocessResult active = preprocessor.process(
                LineSlice.fromUtf8("example.com$$script:has-text(ad)"),
                2
        );
        preprocessor.process(LineSlice.fromUtf8("!#else"), 3);
        PreprocessResult inactive = preprocessor.process(
                LineSlice.fromUtf8("example.com##+js(rmnt, script, ad)"),
                4
        );
        preprocessor.process(LineSlice.fromUtf8("!#endif"), 5);

        assertEquals(1, active.logicalLines().size());
        assertEquals("example.com$$script:has-text(ad)",
                active.logicalLines().getFirst().line().materialize());
        assertTrue(inactive.logicalLines().isEmpty());
        assertTrue(preprocessor.finish().isEmpty());
    }

    @Test
    void evaluatesNestedUboCapabilities() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.UBO);

        preprocessor.process(LineSlice.fromUtf8("!#if ext_ublock"), 1);
        preprocessor.process(LineSlice.fromUtf8("!#if cap_html_filtering"), 2);
        PreprocessResult active = preprocessor.process(
                LineSlice.fromUtf8("example.com##^script:has-text(ad)"),
                3
        );
        preprocessor.process(LineSlice.fromUtf8("!#endif"), 4);
        preprocessor.process(LineSlice.fromUtf8("!#endif"), 5);

        assertEquals(1, active.logicalLines().size());
        assertTrue(preprocessor.finish().isEmpty());
    }

    @Test
    void rejectsInvalidConditionWithoutEmittingEitherBranch() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.ADGUARD);

        PreprocessResult directive = preprocessor.process(
                LineSlice.fromUtf8("!#if (adguard &&)"),
                1
        );
        PreprocessResult firstBranch = preprocessor.process(LineSlice.fromUtf8("first"), 2);
        preprocessor.process(LineSlice.fromUtf8("!#else"), 3);
        PreprocessResult secondBranch = preprocessor.process(LineSlice.fromUtf8("second"), 4);
        preprocessor.process(LineSlice.fromUtf8("!#endif"), 5);

        assertEquals("INVALID_IF_CONDITION", directive.diagnostics().getFirst().code());
        assertTrue(firstBranch.logicalLines().isEmpty());
        assertTrue(secondBranch.logicalLines().isEmpty());
        assertTrue(preprocessor.finish().isEmpty());
    }

    @Test
    void reportsUnexpectedAndUnclosedConditionalDirectives() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.UBO);

        List<PreprocessorDiagnostic> unexpected = preprocessor.process(
                LineSlice.fromUtf8("!#endif"),
                1
        ).diagnostics();
        preprocessor.process(LineSlice.fromUtf8("!#if env_firefox"), 2);

        assertEquals("UNEXPECTED_ENDIF_DIRECTIVE", unexpected.getFirst().code());
        assertEquals("UNCLOSED_IF_DIRECTIVE", preprocessor.finish().getFirst().code());
    }

    @Test
    void restoresUboContinuationAndKeepsStartLine() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.UBO);

        PreprocessResult first = preprocessor.process(
                LineSlice.fromUtf8("||example.com^$domain=a.com| \\"),
                1
        );
        PreprocessResult second = preprocessor.process(LineSlice.fromUtf8("    b.com"), 2);

        assertTrue(first.logicalLines().isEmpty());
        PreprocessedLine logicalLine = second.logicalLines().getFirst();
        assertEquals("||example.com^$domain=a.com|b.com", logicalLine.line().materialize());
        assertEquals(1, logicalLine.physicalStartLine());
        assertTrue(preprocessor.finish().isEmpty());
    }

    @Test
    void doesNotApplyUboContinuationToOtherDialects() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.ABP);

        PreprocessResult result = preprocessor.process(
                LineSlice.fromUtf8("||example.com^ \\"),
                1
        );

        assertEquals(1, result.logicalLines().size());
        assertEquals("||example.com^ \\", result.logicalLines().getFirst().line().materialize());
    }

    @Test
    void reportsElseDirectiveErrors() {
        AdblockPreprocessor unexpected = new AdblockPreprocessor(DialectProfile.UBO);
        assertEquals(
                "UNEXPECTED_ELSE_DIRECTIVE",
                unexpected.process(LineSlice.fromUtf8("!#else"), 1).diagnostics().getFirst().code()
        );

        AdblockPreprocessor duplicate = new AdblockPreprocessor(DialectProfile.UBO);
        duplicate.process(LineSlice.fromUtf8("!#if env_chromium"), 1);
        duplicate.process(LineSlice.fromUtf8("!#else"), 2);
        assertEquals(
                "DUPLICATE_ELSE_DIRECTIVE",
                duplicate.process(LineSlice.fromUtf8("!#else"), 3).diagnostics().getFirst().code()
        );
    }

    @Test
    void reportsInvalidAndUnclosedContinuations() {
        AdblockPreprocessor invalidIndent = new AdblockPreprocessor(DialectProfile.UBO);
        invalidIndent.process(LineSlice.fromUtf8("||example.com^ \\"), 1);
        PreprocessResult invalid = invalidIndent.process(LineSlice.fromUtf8("  next"), 2);
        assertEquals("INVALID_CONTINUATION_INDENT", invalid.diagnostics().getFirst().code());
        assertEquals("  next", invalid.logicalLines().getFirst().line().materialize());

        AdblockPreprocessor unclosed = new AdblockPreprocessor(DialectProfile.UBO);
        unclosed.process(LineSlice.fromUtf8("||example.com^ \\"), 7);
        PreprocessorDiagnostic diagnostic = unclosed.finish().getFirst();
        assertEquals("UNCLOSED_CONTINUATION", diagnostic.code());
        assertEquals(7, diagnostic.physicalLine());
    }

    @Test
    void rejectsNonPositivePhysicalLineNumber() {
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(DialectProfile.UBO);
        assertThrows(
                IllegalArgumentException.class,
                () -> preprocessor.process(LineSlice.fromUtf8("rule"), 0)
        );
    }
}

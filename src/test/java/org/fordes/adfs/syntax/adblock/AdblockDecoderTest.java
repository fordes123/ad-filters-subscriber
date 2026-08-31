package org.fordes.adfs.syntax.adblock;

import org.fordes.adfs.ast.CommentAst;
import org.fordes.adfs.ast.CosmeticRuleAst;
import org.fordes.adfs.ast.CosmeticSyntax;
import org.fordes.adfs.ast.ExtendedAction;
import org.fordes.adfs.ast.ExtensionAst;
import org.fordes.adfs.ast.ExtensionKind;
import org.fordes.adfs.ast.HtmlFilterAst;
import org.fordes.adfs.ast.HtmlFilterSyntax;
import org.fordes.adfs.ast.NetworkAnchor;
import org.fordes.adfs.ast.NetworkAction;
import org.fordes.adfs.ast.NetworkModifierAst;
import org.fordes.adfs.ast.NetworkRuleAst;
import org.fordes.adfs.ast.PreprocessorDirectiveAst;
import org.fordes.adfs.ast.RuleAst;
import org.fordes.adfs.ast.ScriptletRuleAst;
import org.fordes.adfs.ast.ScriptletSyntax;
import org.fordes.adfs.syntax.DecodeResult;
import org.fordes.adfs.syntax.LineSlice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdblockDecoderTest {

    private final AdblockDecoder decoder = new AdblockDecoder();

    @Test
    void decodesExceptionAnchorAndModifiersWithoutLosingSource() {
        String source = "@@||example.com^$script,domain=a.com|~b.com,important";

        NetworkRuleAst ast = decode(source, DialectProfile.UBO, NetworkRuleAst.class);

        assertEquals(source, ast.source().materialize());
        assertEquals(DialectProfile.UBO, ast.dialect());
        assertEquals(NetworkAction.ALLOW, ast.action());
        assertEquals(NetworkAnchor.DOMAIN, ast.leftAnchor());
        assertFalse(ast.rightAnchor());
        assertFalse(ast.regex());
        assertEquals("example.com^", ast.source().materialize(ast.pattern()));
        assertEquals(List.of("script", "domain", "important"), modifierNames(ast));
        assertEquals("a.com|~b.com", modifierValue(ast, 1));
    }

    @Test
    void keepsDollarInsideRegexPattern() {
        NetworkRuleAst ast = decode("/ad$/", DialectProfile.ABP, NetworkRuleAst.class);

        assertTrue(ast.regex());
        assertEquals("ad$", ast.source().materialize(ast.pattern()));
        assertTrue(ast.modifiers().isEmpty());
    }

    @Test
    void decodesRegexFollowedByModifiers() {
        NetworkRuleAst ast = decode(
                "/ads[0-9]+/$script,~third-party",
                DialectProfile.ADBLOCK_BASE,
                NetworkRuleAst.class
        );

        assertTrue(ast.regex());
        assertEquals("ads[0-9]+", ast.source().materialize(ast.pattern()));
        assertEquals(List.of("script", "third-party"), modifierNames(ast));
        assertTrue(ast.modifiers().get(1).negated());
    }

    @Test
    void keepsExtendedSeparatorTokensInsideNetworkRegex() {
        NetworkRuleAst ast = decode(
                "/foo##bar#%#baz/$script",
                DialectProfile.UBO,
                NetworkRuleAst.class
        );

        assertTrue(ast.regex());
        assertEquals("foo##bar#%#baz", ast.source().materialize(ast.pattern()));
        assertEquals(List.of("script"), modifierNames(ast));
    }

    @Test
    void decodesAddressAndRightAnchors() {
        NetworkRuleAst ast = decode(
                "|https://example.com/ad|",
                DialectProfile.ABP,
                NetworkRuleAst.class
        );

        assertEquals(NetworkAnchor.ADDRESS, ast.leftAnchor());
        assertTrue(ast.rightAnchor());
        assertEquals("https://example.com/ad", ast.source().materialize(ast.pattern()));
    }

    @Test
    void doesNotSplitEscapedOrNestedModifierCommas() {
        NetworkRuleAst ast = decode(
                "||example.com^$header=x\\,y,replace=(a,b),script",
                DialectProfile.ADGUARD,
                NetworkRuleAst.class
        );

        assertEquals(List.of("header", "replace", "script"), modifierNames(ast));
        assertEquals("x\\,y", modifierValue(ast, 0));
        assertEquals("(a,b)", modifierValue(ast, 1));
    }

    @Test
    void decodesCosmeticRuleIntoDedicatedAst() {
        CosmeticRuleAst ast = decode(
                "example.com##.advert",
                DialectProfile.ABP,
                CosmeticRuleAst.class
        );

        assertEquals(ExtendedAction.APPLY, ast.action());
        assertEquals(CosmeticSyntax.ELEMENT_HIDING, ast.syntax());
        assertEquals("example.com", ast.source().materialize(ast.domains()));
        assertEquals("##", ast.source().materialize(ast.separator()));
        assertEquals(".advert", ast.source().materialize(ast.body()));
        assertEquals("example.com##.advert", ast.source().materialize());
    }

    @Test
    void decodesUboScriptletException() {
        ScriptletRuleAst ast = decode(
                "example.com#@#+js(set-constant, foo, true)",
                DialectProfile.UBO,
                ScriptletRuleAst.class
        );

        assertEquals(ExtendedAction.EXCEPT, ast.action());
        assertEquals(ScriptletSyntax.UBO_SCRIPTLET, ast.syntax());
        assertEquals("+js(set-constant, foo, true)", ast.source().materialize(ast.body()));
    }

    @Test
    void decodesAdguardScriptletWithNonBasicModifiers() {
        ScriptletRuleAst ast = decode(
                "[$domain=example.com]example.org#%#//scriptlet('abort-on-property-read', 'foo')",
                DialectProfile.ADGUARD,
                ScriptletRuleAst.class
        );

        assertEquals(ScriptletSyntax.ADGUARD_SCRIPTLET, ast.syntax());
        assertEquals(
                "[$domain=example.com]",
                ast.source().materialize(ast.nonBasicModifiers().orElseThrow())
        );
        assertEquals("example.org", ast.source().materialize(ast.domains()));
    }

    @Test
    void decodesUboAndAdguardHtmlFilters() {
        HtmlFilterAst ubo = decode(
                "example.com##^.badstuff",
                DialectProfile.UBO,
                HtmlFilterAst.class
        );
        HtmlFilterAst adguard = decode(
                "example.com$@$.badstuff",
                DialectProfile.ADGUARD,
                HtmlFilterAst.class
        );

        assertEquals(HtmlFilterSyntax.UBO, ubo.syntax());
        assertEquals(ExtendedAction.APPLY, ubo.action());
        assertEquals("^.badstuff", ubo.source().materialize(ubo.body()));
        assertEquals(HtmlFilterSyntax.ADGUARD, adguard.syntax());
        assertEquals(ExtendedAction.EXCEPT, adguard.action());
    }

    @Test
    void keepsRecognizedAdguardJavascriptAsExtensionAst() {
        ExtensionAst ast = decode(
                "example.com#%#window.__adfs = true;",
                DialectProfile.ADGUARD,
                ExtensionAst.class
        );

        assertEquals(ExtensionKind.ADGUARD_JAVASCRIPT, ast.kind());
        assertEquals("window.__adfs = true;", ast.source().materialize(ast.body()));
    }

    @Test
    void decodesPreprocessorDirectiveNameAndValue() {
        PreprocessorDirectiveAst ast = decode(
                "!#if env_chromium",
                DialectProfile.UBO,
                PreprocessorDirectiveAst.class
        );

        assertEquals("if", ast.source().materialize(ast.name()));
        assertEquals("env_chromium", ast.source().materialize(ast.value().orElseThrow()));
    }

    @Test
    void reportsMalformedNetworkRuleAsInvalid() {
        DecodeResult<RuleAst> result = decoder.decode(
                LineSlice.fromUtf8("||$script"),
                DialectProfile.ABP
        );

        DecodeResult.Invalid<?> invalid = assertInstanceOf(DecodeResult.Invalid.class, result);
        assertEquals("EMPTY_NETWORK_PATTERN", invalid.diagnostic().code());
        assertEquals("network pattern 不能为空", invalid.diagnostic().message());
    }

    @Test
    void rejectsMismatchedModifierDelimiters() {
        DecodeResult<RuleAst> result = decoder.decode(
                LineSlice.fromUtf8("||example.com^$replace=(foo]"),
                DialectProfile.ADGUARD
        );

        DecodeResult.Invalid<?> invalid = assertInstanceOf(DecodeResult.Invalid.class, result);
        assertEquals("UNBALANCED_MODIFIER_DELIMITER", invalid.diagnostic().code());
    }

    @Test
    void treatsWhitespaceAfterDirectivePrefixAsComment() {
        CommentAst ast = decode(
                "!# if env_chromium",
                DialectProfile.UBO,
                CommentAst.class
        );

        assertEquals("# if env_chromium", ast.source().materialize(ast.body()));
    }

    private <A extends RuleAst> A decode(
            String source,
            DialectProfile dialect,
            Class<A> astType
    ) {
        DecodeResult<RuleAst> result = decoder.decode(
                LineSlice.fromUtf8(source),
                dialect
        );
        DecodeResult.Decoded<?> decoded = assertInstanceOf(DecodeResult.Decoded.class, result);
        return assertInstanceOf(astType, decoded.ast());
    }

    private static List<String> modifierNames(NetworkRuleAst ast) {
        return ast.modifiers().stream()
                .map(NetworkModifierAst::name)
                .map(ast.source()::materialize)
                .toList();
    }

    private static String modifierValue(NetworkRuleAst ast, int index) {
        return ast.modifiers().get(index).value()
                .map(ast.source()::materialize)
                .orElseThrow();
    }
}

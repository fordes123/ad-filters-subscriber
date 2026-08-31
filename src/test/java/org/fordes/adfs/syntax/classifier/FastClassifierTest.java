package org.fordes.adfs.syntax.classifier;

import org.fordes.adfs.syntax.LineSlice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FastClassifierTest {

    private final FastClassifier classifier = new FastClassifier();

    @ParameterizedTest
    @MethodSource("plainRules")
    void classifiesPlainRuleKinds(String line, RuleKind expected) {
        RuleClassification result = classifier.classify(LineSlice.fromUtf8(line));

        assertEquals(expected, result.kind());
        assertTrue(result.separator().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("extendedRules")
    void recognizesEveryExtendedSeparator(String line, SeparatorKind expected) {
        RuleClassification result = classifier.classify(LineSlice.fromUtf8(line));

        assertEquals(RuleKind.EXTENDED_FILTER, result.kind());
        assertEquals(expected, result.separatorKind().orElseThrow());
    }

    @Test
    void keepsExtendedTokensInsideClosedRegexAsNetworkRule() {
        assertEquals(
                RuleKind.NETWORK,
                classifier.classify(LineSlice.fromUtf8("/foo##bar/")).kind()
        );
    }

    private static Stream<Arguments> plainRules() {
        return Stream.of(
                Arguments.of(" \t", RuleKind.EMPTY),
                Arguments.of("! comment", RuleKind.COMMENT),
                Arguments.of("!#if env", RuleKind.PREPROCESSOR),
                Arguments.of("!# if env", RuleKind.COMMENT),
                Arguments.of("[Adblock Plus 2.0]", RuleKind.METADATA),
                Arguments.of("||example.com^", RuleKind.NETWORK),
                Arguments.of("@@/foo\\/bar/$script", RuleKind.NETWORK)
        );
    }

    private static Stream<Arguments> extendedRules() {
        return Stream.of(
                Arguments.of("example.com#@#+js(test)", SeparatorKind.UBO_SCRIPTLET_EXCEPTION),
                Arguments.of("example.com#@%#//scriptlet('test')", SeparatorKind.ADGUARD_SCRIPTLET_EXCEPTION),
                Arguments.of("example.com#@#^script:has-text(x)", SeparatorKind.UBO_HTML_EXCEPTION),
                Arguments.of("example.com#@$?#div", SeparatorKind.EXTENDED_CSS_EXCEPTION),
                Arguments.of("example.com#@?#div", SeparatorKind.EXTENDED_COSMETIC_EXCEPTION),
                Arguments.of("example.com#@$#style", SeparatorKind.HASH_DOLLAR_EXCEPTION),
                Arguments.of("example.com#@%#js", SeparatorKind.HASH_PERCENT_EXCEPTION),
                Arguments.of("example.com#@#.ad", SeparatorKind.ELEMENT_HIDING_EXCEPTION),
                Arguments.of("example.com$@$script", SeparatorKind.ADGUARD_HTML_EXCEPTION),
                Arguments.of("example.com##+js(test)", SeparatorKind.UBO_SCRIPTLET),
                Arguments.of("example.com#%#//scriptlet('test')", SeparatorKind.ADGUARD_SCRIPTLET),
                Arguments.of("example.com##^script:has-text(x)", SeparatorKind.UBO_HTML),
                Arguments.of("example.com#$?#div", SeparatorKind.EXTENDED_CSS),
                Arguments.of("example.com#?#div", SeparatorKind.EXTENDED_COSMETIC),
                Arguments.of("example.com#$#style", SeparatorKind.HASH_DOLLAR),
                Arguments.of("example.com#%#js", SeparatorKind.HASH_PERCENT),
                Arguments.of("example.com##.ad", SeparatorKind.ELEMENT_HIDING),
                Arguments.of("example.com$$script", SeparatorKind.ADGUARD_HTML)
        );
    }
}

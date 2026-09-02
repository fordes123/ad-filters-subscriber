package org.fordes.adfs.syntax.classifier;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FastClassifier {

    private static final byte[] EXCEPTION = ascii("@@");
    private static final List<SeparatorToken> SEPARATORS = List.of(
            token("#@#+js(", 3, SeparatorKind.UBO_SCRIPTLET_EXCEPTION),
            token("#@%#//scriptlet(", 4, SeparatorKind.ADGUARD_SCRIPTLET_EXCEPTION),
            token("#@#^", 3, SeparatorKind.UBO_HTML_EXCEPTION),
            token("#@$?#", 5, SeparatorKind.EXTENDED_CSS_EXCEPTION),
            token("#@?#", 4, SeparatorKind.EXTENDED_COSMETIC_EXCEPTION),
            token("#@$#", 4, SeparatorKind.HASH_DOLLAR_EXCEPTION),
            token("#@%#", 4, SeparatorKind.HASH_PERCENT_EXCEPTION),
            token("#@#", 3, SeparatorKind.ELEMENT_HIDING_EXCEPTION),
            token("$@$", 3, SeparatorKind.ADGUARD_HTML_EXCEPTION),
            token("##+js(", 2, SeparatorKind.UBO_SCRIPTLET),
            token("#%#//scriptlet(", 3, SeparatorKind.ADGUARD_SCRIPTLET),
            token("##^", 2, SeparatorKind.UBO_HTML),
            token("#$?#", 4, SeparatorKind.EXTENDED_CSS),
            token("#?#", 3, SeparatorKind.EXTENDED_COSMETIC),
            token("#$#", 3, SeparatorKind.HASH_DOLLAR),
            token("#%#", 3, SeparatorKind.HASH_PERCENT),
            token("##", 2, SeparatorKind.ELEMENT_HIDING),
            token("$$", 2, SeparatorKind.ADGUARD_HTML)
    );

    public static RuleClassification classify(LineSlice line) {
        Objects.requireNonNull(line, "line 不能为空");
        int contentStart = firstNonWhitespace(line);
        if (contentStart == line.length()) {
            return plain(RuleKind.EMPTY, contentStart);
        }

        byte first = line.byteAt(contentStart);
        if (first == '!') {
            boolean directive = startsWith(line, contentStart, "!#")
                    && contentStart + 2 < line.length()
                    && !isWhitespace(line.byteAt(contentStart + 2));
            RuleKind kind = directive
                    ? RuleKind.PREPROCESSOR
                    : RuleKind.COMMENT;
            return plain(kind, contentStart);
        }
        if (first == '[' && line.byteAt(lastNonWhitespace(line)) == ']') {
            return plain(RuleKind.METADATA, contentStart);
        }
        if (isRegexNetworkRule(line, contentStart)) {
            return plain(RuleKind.NETWORK, contentStart);
        }

        for (int index = contentStart; index < line.length(); index++) {
            for (SeparatorToken token : SEPARATORS) {
                if (matches(line, index, token.match())) {
                    Span separator = new Span(index, index + token.separatorLength());
                    return new RuleClassification(
                            RuleKind.EXTENDED_FILTER,
                            contentStart,
                            Optional.of(token.kind()),
                            Optional.of(separator)
                    );
                }
            }
        }
        return plain(RuleKind.NETWORK, contentStart);
    }

    private static int firstNonWhitespace(LineSlice line) {
        int index = 0;
        while (index < line.length()) {
            byte value = line.byteAt(index);
            if (value != ' ' && value != '\t') {
                break;
            }
            index++;
        }
        return index;
    }

    private static int lastNonWhitespace(LineSlice line) {
        int index = line.length() - 1;
        while (index >= 0) {
            byte value = line.byteAt(index);
            if (value != ' ' && value != '\t') {
                break;
            }
            index--;
        }
        return index;
    }

    private static boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t';
    }

    private static boolean isRegexNetworkRule(LineSlice line, int contentStart) {
        int patternStart = contentStart;
        if (matches(line, patternStart, EXCEPTION)) {
            patternStart += 2;
        }
        if (patternStart >= line.length() || line.byteAt(patternStart) != '/') {
            return false;
        }

        boolean escaped = false;
        for (int index = patternStart + 1; index < line.length(); index++) {
            byte value = line.byteAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '/' && (index == line.length() - 1 || line.byteAt(index + 1) == '$')) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(LineSlice line, int start, String token) {
        return matches(line, start, ascii(token));
    }

    private static boolean matches(LineSlice line, int start, byte[] token) {
        if (start < 0 || start + token.length > line.length()) {
            return false;
        }
        for (int offset = 0; offset < token.length; offset++) {
            if (line.byteAt(start + offset) != token[offset]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        byte[] result = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            result[index] = (byte) value.charAt(index);
        }
        return result;
    }

    private static RuleClassification plain(RuleKind kind, int contentStart) {
        return new RuleClassification(kind, contentStart, Optional.empty(), Optional.empty());
    }

    private static SeparatorToken token(String match, int separatorLength, SeparatorKind kind) {
        return new SeparatorToken(ascii(match), separatorLength, kind);
    }

    private record SeparatorToken(byte[] match, int separatorLength, SeparatorKind kind) {
        private SeparatorToken {
            Objects.requireNonNull(match, "match 不能为空");
            Objects.requireNonNull(kind, "kind 不能为空");
            if (separatorLength < 1 || separatorLength > match.length) {
                throw new IllegalArgumentException("separatorLength 超出匹配标记范围");
            }
        }
    }
}

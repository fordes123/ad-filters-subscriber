package org.fordes.adfs.syntax.adblock;

import org.fordes.adfs.ast.CommentAst;
import org.fordes.adfs.ast.CosmeticRuleAst;
import org.fordes.adfs.ast.CosmeticSyntax;
import org.fordes.adfs.ast.EmptyAst;
import org.fordes.adfs.ast.ExtendedAction;
import org.fordes.adfs.ast.ExtensionAst;
import org.fordes.adfs.ast.ExtensionKind;
import org.fordes.adfs.ast.HtmlFilterAst;
import org.fordes.adfs.ast.HtmlFilterSyntax;
import org.fordes.adfs.ast.MetadataAst;
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
import org.fordes.adfs.syntax.ParseDiagnostic;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.classifier.FastClassifier;
import org.fordes.adfs.syntax.classifier.RuleClassification;
import org.fordes.adfs.syntax.classifier.SeparatorKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AdblockDecoder {

    public DecodeResult<RuleAst> decode(LineSlice line, DialectProfile dialect) {
        Objects.requireNonNull(line, "line 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");

        RuleClassification classification = FastClassifier.classify(line);
        try {
            RuleAst ast = switch (classification.kind()) {
                case EMPTY -> new EmptyAst(line, dialect);
                case COMMENT -> decodeComment(line, dialect, classification.contentStart());
                case METADATA -> decodeMetadata(line, dialect, classification.contentStart());
                case PREPROCESSOR -> decodeDirective(line, dialect, classification.contentStart());
                case EXTENDED_FILTER -> decodeExtended(line, dialect, classification);
                case NETWORK -> decodeNetwork(line, dialect, classification.contentStart());
            };
            return new DecodeResult.Decoded<>(ast);
        } catch (InvalidRule error) {
            return new DecodeResult.Invalid<>(
                    line,
                    new ParseDiagnostic(error.code, error.offset, error.getMessage())
            );
        }
    }

    private static CommentAst decodeComment(LineSlice line, DialectProfile dialect, int contentStart) {
        return new CommentAst(line, dialect, new Span(contentStart + 1, line.length()));
    }

    private static MetadataAst decodeMetadata(LineSlice line, DialectProfile dialect, int contentStart) {
        int end = lastNonWhitespace(line) + 1;
        return new MetadataAst(line, dialect, new Span(contentStart + 1, end - 1));
    }

    private static PreprocessorDirectiveAst decodeDirective(
            LineSlice line,
            DialectProfile dialect,
            int contentStart
    ) {
        int cursor = contentStart + 2;
        int nameStart = cursor;
        while (cursor < line.length() && !isWhitespace(line.byteAt(cursor))) {
            cursor++;
        }
        if (nameStart == cursor) {
            throw invalid("EMPTY_DIRECTIVE", nameStart, "preprocessor directive 名称不能为空");
        }

        int valueStart = skipWhitespace(line, cursor, line.length());
        int valueEnd = lastNonWhitespace(line) + 1;
        Optional<Span> value = valueStart < valueEnd
                ? Optional.of(new Span(valueStart, valueEnd))
                : Optional.empty();
        return new PreprocessorDirectiveAst(
                line,
                dialect,
                new Span(nameStart, cursor),
                value
        );
    }

    private static RuleAst decodeExtended(
            LineSlice line,
            DialectProfile dialect,
            RuleClassification classification
    ) {
        Span separator = classification.separator().orElseThrow();
        SeparatorKind kind = classification.separatorKind().orElseThrow();
        int domainStart = classification.contentStart();
        Optional<Span> nonBasicModifiers = Optional.empty();

        if (matches(line, domainStart, '[', '$')) {
            int modifierEnd = findUnescaped(line, domainStart + 2, separator.start(), ']');
            if (modifierEnd < 0) {
                throw invalid(
                        "UNCLOSED_NON_BASIC_MODIFIERS",
                        domainStart,
                        "non-basic modifier block 未闭合"
                );
            }
            nonBasicModifiers = Optional.of(new Span(domainStart, modifierEnd + 1));
            domainStart = modifierEnd + 1;
        }

        if (separator.start() < domainStart) {
            throw invalid("INVALID_EXTENDED_PREFIX", separator.start(), "extended filter 前缀结构无效");
        }
        Span domains = new Span(domainStart, separator.start());
        Span body = new Span(separator.end(), line.length());
        if (body.isEmpty()) {
            throw invalid("EMPTY_EXTENDED_BODY", separator.end(), "extended filter body 不能为空");
        }

        return switch (kind) {
            case ELEMENT_HIDING -> cosmetic(
                    line, dialect, ExtendedAction.APPLY, CosmeticSyntax.ELEMENT_HIDING,
                    nonBasicModifiers, domains, separator, body);
            case ELEMENT_HIDING_EXCEPTION -> cosmetic(
                    line, dialect, ExtendedAction.EXCEPT, CosmeticSyntax.ELEMENT_HIDING,
                    nonBasicModifiers, domains, separator, body);
            case EXTENDED_COSMETIC -> cosmetic(
                    line, dialect, ExtendedAction.APPLY, CosmeticSyntax.EXTENDED_SELECTOR,
                    nonBasicModifiers, domains, separator, body);
            case EXTENDED_COSMETIC_EXCEPTION -> cosmetic(
                    line, dialect, ExtendedAction.EXCEPT, CosmeticSyntax.EXTENDED_SELECTOR,
                    nonBasicModifiers, domains, separator, body);
            case EXTENDED_CSS -> cosmetic(
                    line, dialect, ExtendedAction.APPLY, CosmeticSyntax.EXTENDED_CSS,
                    nonBasicModifiers, domains, separator, body);
            case EXTENDED_CSS_EXCEPTION -> cosmetic(
                    line, dialect, ExtendedAction.EXCEPT, CosmeticSyntax.EXTENDED_CSS,
                    nonBasicModifiers, domains, separator, body);
            case UBO_SCRIPTLET -> scriptlet(
                    line, dialect, ExtendedAction.APPLY, ScriptletSyntax.UBO_SCRIPTLET,
                    nonBasicModifiers, domains, separator, body);
            case UBO_SCRIPTLET_EXCEPTION -> scriptlet(
                    line, dialect, ExtendedAction.EXCEPT, ScriptletSyntax.UBO_SCRIPTLET,
                    nonBasicModifiers, domains, separator, body);
            case ADGUARD_SCRIPTLET -> scriptlet(
                    line, dialect, ExtendedAction.APPLY, ScriptletSyntax.ADGUARD_SCRIPTLET,
                    nonBasicModifiers, domains, separator, body);
            case ADGUARD_SCRIPTLET_EXCEPTION -> scriptlet(
                    line, dialect, ExtendedAction.EXCEPT, ScriptletSyntax.ADGUARD_SCRIPTLET,
                    nonBasicModifiers, domains, separator, body);
            case UBO_HTML -> html(
                    line, dialect, ExtendedAction.APPLY, HtmlFilterSyntax.UBO,
                    nonBasicModifiers, domains, separator, body);
            case UBO_HTML_EXCEPTION -> html(
                    line, dialect, ExtendedAction.EXCEPT, HtmlFilterSyntax.UBO,
                    nonBasicModifiers, domains, separator, body);
            case ADGUARD_HTML -> html(
                    line, dialect, ExtendedAction.APPLY, HtmlFilterSyntax.ADGUARD,
                    nonBasicModifiers, domains, separator, body);
            case ADGUARD_HTML_EXCEPTION -> html(
                    line, dialect, ExtendedAction.EXCEPT, HtmlFilterSyntax.ADGUARD,
                    nonBasicModifiers, domains, separator, body);
            case HASH_DOLLAR -> decodeHashDollar(
                    line, dialect, ExtendedAction.APPLY,
                    nonBasicModifiers, domains, separator, body);
            case HASH_DOLLAR_EXCEPTION -> decodeHashDollar(
                    line, dialect, ExtendedAction.EXCEPT,
                    nonBasicModifiers, domains, separator, body);
            case HASH_PERCENT -> extension(
                    line, dialect, ExtendedAction.APPLY, ExtensionKind.ADGUARD_JAVASCRIPT,
                    nonBasicModifiers, domains, separator, body);
            case HASH_PERCENT_EXCEPTION -> extension(
                    line, dialect, ExtendedAction.EXCEPT, ExtensionKind.ADGUARD_JAVASCRIPT,
                    nonBasicModifiers, domains, separator, body);
        };
    }

    private static RuleAst decodeHashDollar(
            LineSlice line,
            DialectProfile dialect,
            ExtendedAction action,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        return switch (dialect) {
            case ABP -> scriptlet(
                    line, dialect, action, ScriptletSyntax.ABP_SNIPPET,
                    nonBasicModifiers, domains, separator, body);
            case UBO, ADGUARD -> cosmetic(
                    line, dialect, action, CosmeticSyntax.CSS_INJECTION,
                    nonBasicModifiers, domains, separator, body);
            case ADBLOCK_BASE -> extension(
                    line, dialect, action, ExtensionKind.DIALECT_SPECIFIC_EXTENDED_FILTER,
                    nonBasicModifiers, domains, separator, body);
        };
    }

    private static CosmeticRuleAst cosmetic(
            LineSlice line,
            DialectProfile dialect,
            ExtendedAction action,
            CosmeticSyntax syntax,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        return new CosmeticRuleAst(
                line, dialect, action, syntax,
                nonBasicModifiers, domains, separator, body);
    }

    private static ScriptletRuleAst scriptlet(
            LineSlice line,
            DialectProfile dialect,
            ExtendedAction action,
            ScriptletSyntax syntax,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        return new ScriptletRuleAst(
                line, dialect, action, syntax,
                nonBasicModifiers, domains, separator, body);
    }

    private static HtmlFilterAst html(
            LineSlice line,
            DialectProfile dialect,
            ExtendedAction action,
            HtmlFilterSyntax syntax,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        return new HtmlFilterAst(
                line, dialect, action, syntax,
                nonBasicModifiers, domains, separator, body);
    }

    private static ExtensionAst extension(
            LineSlice line,
            DialectProfile dialect,
            ExtendedAction action,
            ExtensionKind kind,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        return new ExtensionAst(
                line, dialect, action, kind,
                nonBasicModifiers, domains, separator, body);
    }

    private static NetworkRuleAst decodeNetwork(
            LineSlice line,
            DialectProfile dialect,
            int contentStart
    ) {
        int cursor = contentStart;
        NetworkAction action = matches(line, cursor, '@', '@')
                ? NetworkAction.ALLOW
                : NetworkAction.BLOCK;
        if (action == NetworkAction.ALLOW) {
            cursor += 2;
        }

        NetworkAnchor leftAnchor = NetworkAnchor.NONE;
        if (matches(line, cursor, '|', '|')) {
            leftAnchor = NetworkAnchor.DOMAIN;
            cursor += 2;
        } else if (matches(line, cursor, '|')) {
            leftAnchor = NetworkAnchor.ADDRESS;
            cursor++;
        }

        int regexEnd = findRegexEnd(line, cursor);
        boolean regex = regexEnd >= 0;
        int optionMarker;
        int patternStart;
        int patternEnd;

        if (regex) {
            patternStart = cursor + 1;
            patternEnd = regexEnd;
            optionMarker = regexEnd + 1 < line.length() ? regexEnd + 1 : -1;
        } else {
            optionMarker = findUnescaped(line, cursor, line.length(), '$');
            patternStart = cursor;
            patternEnd = optionMarker >= 0 ? optionMarker : line.length();
        }

        boolean rightAnchor = false;
        if (!regex && patternEnd > patternStart && line.byteAt(patternEnd - 1) == '|'
                && !isEscaped(line, patternEnd - 1, patternStart)) {
            rightAnchor = true;
            patternEnd--;
        }
        Optional<Span> modifierBlock = Optional.empty();
        List<NetworkModifierAst> modifiers = List.of();
        if (optionMarker >= 0) {
            if (line.byteAt(optionMarker) != '$') {
                throw invalid(
                        "TRAILING_REGEX_CONTENT",
                        optionMarker,
                        "regex 结束符后存在无法识别的内容"
                );
            }
            int modifierStart = optionMarker + 1;
            if (modifierStart == line.length()) {
                throw invalid("EMPTY_MODIFIER_BLOCK", modifierStart, "modifier block 不能为空");
            }
            modifierBlock = Optional.of(new Span(modifierStart, line.length()));
            modifiers = parseModifiers(line, modifierStart, line.length());
        }

        if (patternStart == patternEnd
                && (leftAnchor != NetworkAnchor.NONE || modifierBlock.isEmpty())) {
            throw invalid("EMPTY_NETWORK_PATTERN", patternStart, "network pattern 不能为空");
        }

        return new NetworkRuleAst(
                line,
                dialect,
                action,
                leftAnchor,
                rightAnchor,
                regex,
                new Span(patternStart, patternEnd),
                modifierBlock,
                modifiers
        );
    }

    private static List<NetworkModifierAst> parseModifiers(LineSlice line, int start, int end) {
        List<NetworkModifierAst> modifiers = new ArrayList<>();
        int modifierStart = start;
        Deque<Byte> delimiters = new ArrayDeque<>();
        boolean escaped = false;
        boolean regularExpression = false;
        boolean regularExpressionConsumed = false;

        for (int index = start; index < end; index++) {
            byte value = line.byteAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (regularExpression) {
                if (value == '/') {
                    regularExpression = false;
                    regularExpressionConsumed = true;
                }
                continue;
            }
            if (!regularExpressionConsumed
                    && value == '/'
                    && startsModifierRegularExpression(line, modifierStart, index)) {
                regularExpression = true;
                continue;
            }
            if (regularExpressionConsumed) {
                if (value == ',') {
                    modifiers.add(parseModifier(line, modifierStart, index));
                    modifierStart = index + 1;
                    regularExpressionConsumed = false;
                }
                continue;
            }
            if (value == '(' || value == '[' || value == '{') {
                delimiters.push(value);
                continue;
            }
            if (value == ')' || value == ']' || value == '}') {
                if (delimiters.isEmpty() || !closes(delimiters.peek(), value)) {
                    throw invalid(
                            "UNBALANCED_MODIFIER_DELIMITER",
                            index,
                            "modifier value 的嵌套定界符不匹配"
                    );
                }
                delimiters.pop();
                continue;
            }
            if (value == ',' && delimiters.isEmpty()) {
                modifiers.add(parseModifier(line, modifierStart, index));
                modifierStart = index + 1;
                regularExpressionConsumed = false;
            }
        }

        if (!delimiters.isEmpty()) {
            throw invalid(
                    "UNCLOSED_MODIFIER_DELIMITER",
                    end,
                    "modifier value 的嵌套定界符未闭合"
            );
        }
        modifiers.add(parseModifier(line, modifierStart, end));
        return List.copyOf(modifiers);
    }

    private static boolean startsModifierRegularExpression(
            LineSlice line,
            int modifierStart,
            int slash
    ) {
        int equals = findUnescaped(line, modifierStart, slash, '=');
        if (equals < 0) {
            return false;
        }
        if (slash == equals + 1) {
            return true;
        }
        if (slash == equals + 2 && line.byteAt(equals + 1) == '~') {
            return true;
        }
        return slash > equals + 1 && line.byteAt(slash - 1) == ':';
    }

    private static boolean closes(byte opening, byte closing) {
        return opening == '(' && closing == ')'
                || opening == '[' && closing == ']'
                || opening == '{' && closing == '}';
    }

    private static NetworkModifierAst parseModifier(LineSlice line, int start, int end) {
        if (start == end) {
            throw invalid("EMPTY_MODIFIER_NAME", start, "modifier name 不能为空");
        }

        int equals = findUnescaped(line, start, end, '=');
        int nameEnd = equals >= 0 ? equals : end;
        boolean negated = line.byteAt(start) == '~';
        int nameStart = negated ? start + 1 : start;
        if (nameStart == nameEnd) {
            throw invalid("EMPTY_MODIFIER_NAME", nameStart, "modifier name 不能为空");
        }

        Optional<Span> value = equals >= 0
                ? Optional.of(new Span(equals + 1, end))
                : Optional.empty();
        return new NetworkModifierAst(
                new Span(start, end),
                new Span(nameStart, nameEnd),
                value,
                negated
        );
    }

    private static int findRegexEnd(LineSlice line, int start) {
        if (line.byteAt(start) != '/') {
            return -1;
        }

        boolean escaped = false;
        for (int index = start + 1; index < line.length(); index++) {
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
                return index;
            }
        }
        return -1;
    }

    private static int findUnescaped(LineSlice line, int start, int end, char target) {
        boolean escaped = false;
        for (int index = start; index < end; index++) {
            byte value = line.byteAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == target) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isEscaped(LineSlice line, int index, int lowerBound) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= lowerBound && line.byteAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static int skipWhitespace(LineSlice line, int start, int end) {
        int cursor = start;
        while (cursor < end && isWhitespace(line.byteAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int lastNonWhitespace(LineSlice line) {
        int cursor = line.length() - 1;
        while (cursor >= 0 && isWhitespace(line.byteAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    private static boolean isWhitespace(byte value) {
        return value == ' ' || value == '\t';
    }

    private static boolean matches(LineSlice line, int start, char... expected) {
        if (start < 0 || start + expected.length > line.length()) {
            return false;
        }
        for (int offset = 0; offset < expected.length; offset++) {
            if (line.byteAt(start + offset) != expected[offset]) {
                return false;
            }
        }
        return true;
    }

    private static InvalidRule invalid(String code, int offset, String message) {
        return new InvalidRule(code, offset, message);
    }

    private static final class InvalidRule extends RuntimeException {

        private final String code;
        private final int offset;

        private InvalidRule(String code, int offset, String message) {
            super(message, null, false, false);
            this.code = code;
            this.offset = offset;
        }
    }
}

package org.fordes.adfs.syntax.selector;

import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 在共享 selector AST 上执行 uBO 与 ABP 的过程型伪类转换。
 */
public final class SelectorTranscoder {

    private static final Set<String> STANDARD_FUNCTIONS = Set.of(
            "dir", "has", "is", "lang", "not", "nth-child", "nth-last-child",
            "nth-last-of-type", "nth-of-type", "where"
    );
    private static final Set<String> UNSUPPORTED_FUNCTIONS = Set.of(
            "-abp-properties", "matches-attr", "matches-css", "matches-css-after",
            "matches-css-before", "matches-media", "matches-path", "matches-prop",
            "min-text-length", "others", "remove-attr", "remove-class", "style",
            "upward", "watch-attr", "xpath"
    );

    private SelectorTranscoder() {
    }

    public static Optional<Conversion> transcode(String source, DialectProfile target) {
        if (target != DialectProfile.ABP && target != DialectProfile.UBO) {
            return Optional.empty();
        }
        return parse(source).flatMap(ast -> render(ast, target));
    }

    private static Optional<Conversion> render(Selector ast, DialectProfile target) {
        List<Part> parts = ast.parts();
        boolean remove = false;
        if (parts.getLast() instanceof Function function
                && function.name().equals("remove")
                && function.argument().isBlank()) {
            remove = true;
            parts = parts.subList(0, parts.size() - 1);
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder output = new StringBuilder();
        boolean procedural = false;
        for (Part part : parts) {
            if (part instanceof Text text) {
                output.append(text.value());
                continue;
            }
            Function function = (Function) part;
            Optional<String> name = targetName(function.name(), target);
            if (name.isEmpty()) {
                return Optional.empty();
            }
            String argument = function.argument();
            if (function.selectorArgument().isPresent()) {
                Optional<Conversion> nested = render(function.selectorArgument().orElseThrow(), target);
                if (nested.isEmpty() || nested.orElseThrow().remove()) {
                    return Optional.empty();
                }
                Conversion nestedConversion = nested.orElseThrow();
                argument = nestedConversion.selector();
                procedural = procedural || nestedConversion.procedural();
            }
            if (isProcedural(function.name())) {
                procedural = true;
            }
            output.append(':').append(name.orElseThrow()).append('(').append(argument).append(')');
        }
        return Optional.of(new Conversion(output.toString(), procedural, remove));
    }

    private static Optional<String> targetName(String sourceName, DialectProfile target) {
        if (UNSUPPORTED_FUNCTIONS.contains(sourceName) || sourceName.equals("remove")) {
            return Optional.empty();
        }
        return switch (sourceName) {
            case "has", "-abp-has" -> Optional.of(target == DialectProfile.ABP ? "-abp-has" : "has");
            case "has-text", "contains", "-abp-contains" ->
                    Optional.of(target == DialectProfile.ABP ? "-abp-contains" : "has-text");
            default -> STANDARD_FUNCTIONS.contains(sourceName)
                    ? Optional.of(sourceName)
                    : Optional.empty();
        };
    }

    private static boolean isProcedural(String name) {
        return name.equals("has")
                || name.equals("has-text")
                || name.equals("contains")
                || name.equals("-abp-has")
                || name.equals("-abp-contains");
    }

    private static Optional<Selector> parse(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        List<Part> parts = new ArrayList<>();
        int textStart = 0;
        char quote = 0;
        boolean escaped = false;
        int attributeDepth = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                continue;
            }
            if (character == '[') {
                attributeDepth++;
                continue;
            }
            if (character == ']') {
                if (attributeDepth == 0) {
                    return Optional.empty();
                }
                attributeDepth--;
                continue;
            }
            if (character != ':' || attributeDepth != 0) {
                continue;
            }
            int nameEnd = functionNameEnd(source, index + 1);
            if (nameEnd == index + 1 || nameEnd >= source.length()
                    || source.charAt(nameEnd) != '(') {
                continue;
            }
            int closing = closingParenthesis(source, nameEnd);
            if (closing < 0) {
                return Optional.empty();
            }
            if (textStart < index) {
                parts.add(new Text(source.substring(textStart, index)));
            }
            String name = source.substring(index + 1, nameEnd).toLowerCase(Locale.ROOT);
            String argument = source.substring(nameEnd + 1, closing);
            Optional<Selector> nested = isSelectorArgument(name)
                    ? parse(argument)
                    : Optional.empty();
            parts.add(new Function(name, argument, nested));
            index = closing;
            textStart = closing + 1;
        }
        if (quote != 0 || attributeDepth != 0) {
            return Optional.empty();
        }
        if (textStart < source.length()) {
            parts.add(new Text(source.substring(textStart)));
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(new Selector(parts));
    }

    private static boolean isSelectorArgument(String name) {
        return name.equals("has") || name.equals("-abp-has");
    }

    private static int functionNameEnd(String source, int start) {
        int cursor = start;
        while (cursor < source.length() && isFunctionNameCharacter(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isFunctionNameCharacter(char value) {
        return value <= 0x7F
                && (Character.isLetterOrDigit(value) || value == '-' || value == '_');
    }

    private static int closingParenthesis(String source, int opening) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = opening; index < source.length(); index++) {
            char character = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            } else if (character == ')' && depth < 0) {
                return -1;
            }
        }
        return -1;
    }

    private record Selector(List<Part> parts) {

        private Selector {
            parts = List.copyOf(parts);
        }
    }

    private sealed interface Part permits Text, Function {
    }

    private record Text(String value) implements Part {
    }

    private record Function(
            String name,
            String argument,
            Optional<Selector> selectorArgument
    ) implements Part {
    }

    public record Conversion(String selector, boolean procedural, boolean remove) {
    }
}

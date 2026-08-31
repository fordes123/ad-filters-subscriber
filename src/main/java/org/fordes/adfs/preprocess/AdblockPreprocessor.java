package org.fordes.adfs.preprocess;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class AdblockPreprocessor {

    private final DialectProfile dialect;
    private final Deque<ConditionalFrame> conditions;
    private ByteArrayOutputStream pendingLogicalLine;
    private long pendingPhysicalStart;

    public AdblockPreprocessor(DialectProfile dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect 不能为空");
        this.conditions = new ArrayDeque<>();
    }

    public PreprocessResult process(LineSlice physicalLine, long physicalLineNumber) {
        Objects.requireNonNull(physicalLine, "physicalLine 不能为空");
        if (physicalLineNumber < 1) {
            throw new IllegalArgumentException("physicalLineNumber 必须大于 0");
        }

        List<PreprocessedLine> candidates = new ArrayList<>(1);
        List<PreprocessorDiagnostic> diagnostics = new ArrayList<>(1);
        if (dialect == DialectProfile.UBO && pendingLogicalLine != null) {
            continuePendingLine(physicalLine, physicalLineNumber, candidates, diagnostics);
        } else {
            acceptStandaloneLine(physicalLine, physicalLineNumber, candidates);
        }

        List<PreprocessedLine> activeLines = new ArrayList<>(candidates.size());
        for (PreprocessedLine candidate : candidates) {
            acceptLogicalLine(candidate, activeLines, diagnostics);
        }
        return new PreprocessResult(activeLines, diagnostics);
    }

    public List<PreprocessorDiagnostic> finish() {
        List<PreprocessorDiagnostic> diagnostics = new ArrayList<>();
        if (pendingLogicalLine != null) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "UNCLOSED_CONTINUATION",
                    pendingPhysicalStart,
                    "uBO 续行标记后缺少续行内容"
            ));
        }
        conditions.stream()
                .map(frame -> new PreprocessorDiagnostic(
                        "UNCLOSED_IF_DIRECTIVE",
                        frame.physicalLine(),
                        "!#if 缺少对应的 !#endif"
                ))
                .forEach(diagnostics::add);
        return List.copyOf(diagnostics);
    }

    private void continuePendingLine(
            LineSlice physicalLine,
            long physicalLineNumber,
            List<PreprocessedLine> logicalLines,
            List<PreprocessorDiagnostic> diagnostics
    ) {
        if (!startsWithFourSpaces(physicalLine)) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "INVALID_CONTINUATION_INDENT",
                    pendingPhysicalStart,
                    "uBO 续行内容必须缩进四个空格"
            ));
            clearPendingLine();
            acceptStandaloneLine(physicalLine, physicalLineNumber, logicalLines);
            return;
        }

        appendContinuation(pendingLogicalLine, physicalLine);
        if (endsWithContinuationMarker(physicalLine)) {
            return;
        }

        byte[] logicalBytes = pendingLogicalLine.toByteArray();
        LineSlice logicalSource = new LineSlice(logicalBytes, 0, logicalBytes.length);
        logicalLines.add(new PreprocessedLine(logicalSource, pendingPhysicalStart));
        clearPendingLine();
    }

    private void acceptStandaloneLine(
            LineSlice physicalLine,
            long physicalLineNumber,
            List<PreprocessedLine> logicalLines
    ) {
        if (dialect == DialectProfile.UBO && endsWithContinuationMarker(physicalLine)) {
            pendingLogicalLine = new ByteArrayOutputStream(physicalLine.length());
            pendingLogicalLine.write(
                    physicalLine.buffer(),
                    physicalLine.start(),
                    physicalLine.length() - 2
            );
            pendingPhysicalStart = physicalLineNumber;
            return;
        }
        logicalLines.add(new PreprocessedLine(physicalLine, physicalLineNumber));
    }

    private void acceptLogicalLine(
            PreprocessedLine candidate,
            List<PreprocessedLine> activeLines,
            List<PreprocessorDiagnostic> diagnostics
    ) {
        Directive directive = directive(candidate.line());
        if (directive == null) {
            if (isActive()) {
                activeLines.add(candidate);
            }
            return;
        }
        switch (directive.name()) {
            case "if" -> acceptIf(directive.value(), candidate.physicalStartLine(), diagnostics);
            case "else" -> validateElse(candidate.physicalStartLine(), diagnostics);
            case "endif" -> validateEndIf(candidate.physicalStartLine(), diagnostics);
            default -> {
            }
        }
    }

    private void acceptIf(
            String expression,
            long physicalLineNumber,
            List<PreprocessorDiagnostic> diagnostics
    ) {
        boolean parentActive = isActive();
        final boolean matched;
        try {
            matched = new ConditionExpression(expression, dialect).evaluate();
        } catch (IllegalArgumentException error) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "INVALID_IF_CONDITION",
                    physicalLineNumber,
                    "!#if 条件无效: " + error.getMessage()
            ));
            conditions.push(new ConditionalFrame(
                    physicalLineNumber, false, false, false, false));
            return;
        }
        conditions.push(new ConditionalFrame(
                physicalLineNumber,
                parentActive,
                matched,
                false,
                parentActive && matched
        ));
    }

    private void validateElse(
            long physicalLineNumber,
            List<PreprocessorDiagnostic> diagnostics
    ) {
        ConditionalFrame frame = conditions.peek();
        if (frame == null) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "UNEXPECTED_ELSE_DIRECTIVE",
                    physicalLineNumber,
                    "!#else 没有对应的 !#if"
            ));
            return;
        }
        if (frame.elseSeen()) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "DUPLICATE_ELSE_DIRECTIVE",
                    physicalLineNumber,
                    "同一 !#if 中只能出现一次 !#else"
            ));
            return;
        }
        conditions.pop();
        conditions.push(new ConditionalFrame(
                frame.physicalLine(),
                frame.parentActive(),
                frame.conditionMatched(),
                true,
                frame.parentActive() && !frame.conditionMatched()
        ));
    }

    private void validateEndIf(
            long physicalLineNumber,
            List<PreprocessorDiagnostic> diagnostics
    ) {
        if (conditions.isEmpty()) {
            diagnostics.add(new PreprocessorDiagnostic(
                    "UNEXPECTED_ENDIF_DIRECTIVE",
                    physicalLineNumber,
                    "!#endif 没有对应的 !#if"
            ));
            return;
        }
        conditions.pop();
    }

    private void clearPendingLine() {
        pendingLogicalLine = null;
        pendingPhysicalStart = 0;
    }

    private boolean isActive() {
        return conditions.isEmpty() || conditions.peek().active();
    }

    private static Directive directive(LineSlice line) {
        int cursor = 0;
        while (cursor < line.length() && isWhitespace(line.byteAt(cursor))) {
            cursor++;
        }
        if (!matches(line, cursor, '!', '#')) {
            return null;
        }

        cursor += 2;
        int start = cursor;
        while (cursor < line.length() && !isWhitespace(line.byteAt(cursor))) {
            cursor++;
        }
        String name = start == cursor ? "" : line.materialize(new Span(start, cursor));
        while (cursor < line.length() && isWhitespace(line.byteAt(cursor))) {
            cursor++;
        }
        String value = cursor < line.length()
                ? line.materialize(new Span(cursor, line.length())).trim()
                : "";
        return new Directive(name, value);
    }

    private static void appendContinuation(
            ByteArrayOutputStream pending,
            LineSlice continuation
    ) {
        int suffixLength = endsWithContinuationMarker(continuation) ? 2 : 0;
        pending.write(
                continuation.buffer(),
                continuation.start() + 4,
                continuation.length() - 4 - suffixLength
        );
    }

    private static boolean endsWithContinuationMarker(LineSlice line) {
        return line.length() >= 2
                && line.byteAt(line.length() - 2) == ' '
                && line.byteAt(line.length() - 1) == '\\';
    }

    private static boolean startsWithFourSpaces(LineSlice line) {
        return matches(line, 0, ' ', ' ', ' ', ' ');
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

    private record Directive(String name, String value) {
        private Directive {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(value, "value 不能为空");
        }
    }

    private record ConditionalFrame(
            long physicalLine,
            boolean parentActive,
            boolean conditionMatched,
            boolean elseSeen,
            boolean active
    ) {
    }

    private static final class ConditionExpression {

        private final String expression;
        private final DialectProfile dialect;
        private int cursor;

        private ConditionExpression(String expression, DialectProfile dialect) {
            this.expression = Objects.requireNonNull(expression, "expression 不能为空");
            this.dialect = Objects.requireNonNull(dialect, "dialect 不能为空");
        }

        private boolean evaluate() {
            if (expression.isBlank()) {
                throw new IllegalArgumentException("条件不能为空");
            }
            boolean result = parseOr();
            skipWhitespace();
            if (cursor != expression.length()) {
                throw new IllegalArgumentException("无法识别的位置 " + cursor);
            }
            return result;
        }

        private boolean parseOr() {
            boolean result = parseAnd();
            while (consume("||")) {
                boolean right = parseAnd();
                result = result || right;
            }
            return result;
        }

        private boolean parseAnd() {
            boolean result = parseUnary();
            while (consume("&&")) {
                boolean right = parseUnary();
                result = result && right;
            }
            return result;
        }

        private boolean parseUnary() {
            skipWhitespace();
            if (consume("!")) {
                return !parseUnary();
            }
            if (consume("(")) {
                boolean result = parseOr();
                if (!consume(")")) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return result;
            }
            return constant(parseIdentifier());
        }

        private String parseIdentifier() {
            skipWhitespace();
            int start = cursor;
            while (cursor < expression.length()) {
                char character = expression.charAt(cursor);
                if (!Character.isLetterOrDigit(character) && character != '_') {
                    break;
                }
                cursor++;
            }
            if (start == cursor) {
                throw new IllegalArgumentException("缺少条件常量");
            }
            return expression.substring(start, cursor);
        }

        private boolean constant(String name) {
            return switch (name) {
                case "true" -> true;
                case "false" -> false;
                case "adguard" -> dialect == DialectProfile.ADGUARD;
                case "ext_ublock" -> dialect == DialectProfile.UBO;
                case "ext_abp" -> dialect == DialectProfile.ABP;
                case "cap_html_filtering" -> dialect == DialectProfile.ADGUARD
                        || dialect == DialectProfile.UBO;
                default -> false;
            };
        }

        private boolean consume(String token) {
            skipWhitespace();
            if (!expression.startsWith(token, cursor)) {
                return false;
            }
            cursor += token.length();
            return true;
        }

        private void skipWhitespace() {
            while (cursor < expression.length()
                    && Character.isWhitespace(expression.charAt(cursor))) {
                cursor++;
            }
        }
    }
}

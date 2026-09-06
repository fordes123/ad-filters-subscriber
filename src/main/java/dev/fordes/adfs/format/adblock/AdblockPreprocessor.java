package dev.fordes.adfs.format.adblock;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.fordes.adfs.config.EffectiveConfig.InputLimits;
import dev.fordes.adfs.config.EffectiveConfig.PreprocessorConfig;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.source.BoundedLineReader;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;
import dev.fordes.adfs.source.SourceStream;

final class AdblockPreprocessor {

    private static final char UTF8_BOM = '\ufeff';
    private final InputLimits limits;
    private final PreprocessorConfig config;
    private final Set<String> trueTokens;
    private final Deque<ConditionalFrame> conditions = new ArrayDeque<>();
    private final Set<URI> activeSources = new HashSet<>();

    AdblockPreprocessor(InputLimits limits, PreprocessorConfig config, Set<String> builtInTokens) {
        this.limits = limits;
        this.config = config;
        Set<String> tokens = new HashSet<>(config.trueTokens());
        tokens.addAll(builtInTokens);
        this.trueTokens = Set.copyOf(tokens);
    }

    void process(SourceSession session, Consumer<SourceLine> consumer) {
        processStream(session, session.root(), consumer);
        if (!conditions.isEmpty()) {
            throw new RuleProcessingException("Adblock 条件块在来源结束时未闭合: depth=" + conditions.size());
        }
    }

    private void processStream(SourceSession session, SourceStream stream, Consumer<SourceLine> consumer) {
        URI normalized = stream.location().normalize();
        if (!activeSources.add(normalized)) {
            throw new RuleProcessingException("Adblock include 形成循环: source=" + stream.description());
        }
        int initialDepth = conditions.size();
        boolean first = true;
        try (BoundedLineReader reader = new BoundedLineReader(stream, limits.maxLineLength())) {
            Optional<SourceLine> next;
            while ((next = reader.readLine()).isPresent()) {
                SourceLine line = next.orElseThrow();
                if (first && !line.text().isEmpty() && line.text().charAt(0) == UTF8_BOM) {
                    line = new SourceLine(line.source(), line.lineNumber(), line.text().substring(1));
                }
                first = false;
                String stripped = line.text().strip();
                if (stripped.startsWith("!#if ")) {
                    openCondition(stripped.substring(5), line);
                } else if (stripped.equals("!#else")) {
                    switchBranch(line, initialDepth);
                } else if (stripped.equals("!#endif")) {
                    closeCondition(line, initialDepth);
                } else if (stripped.startsWith("!#include ")) {
                    if (isActive()) {
                        String reference = unquote(stripped.substring(10).strip(), line);
                        processStream(session, session.openInclude(stream, reference), consumer);
                    }
                } else if (stripped.startsWith("!#")) {
                    throw failure(line, "未知 Adblock 预处理指令");
                } else if (isActive()) {
                    consumer.accept(line);
                }
            }
            if (conditions.size() != initialDepth) {
                throw failure(stream.description(), "include 或来源内的条件块未闭合");
            }
        } finally {
            activeSources.remove(normalized);
        }
    }

    private void openCondition(String expression, SourceLine line) {
        if (conditions.size() == config.maxDepth()) {
            throw failure(line, "Adblock 条件嵌套超过上限: max-depth=" + config.maxDepth());
        }
        boolean parentActive = isActive();
        boolean condition = BooleanExpression.evaluate(expression.strip(), trueTokens);
        conditions.push(new ConditionalFrame(parentActive, condition, false, parentActive && condition));
    }

    private void switchBranch(SourceLine line, int initialDepth) {
        if (conditions.size() <= initialDepth) {
            throw failure(line, "Adblock !#else 没有对应 !#if");
        }
        ConditionalFrame frame = conditions.pop();
        if (frame.elseSeen()) {
            throw failure(line, "Adblock 条件块包含重复 !#else");
        }
        conditions.push(new ConditionalFrame(frame.parentActive(), frame.condition(), true,
                frame.parentActive() && !frame.condition()));
    }

    private void closeCondition(SourceLine line, int initialDepth) {
        if (conditions.size() <= initialDepth) {
            throw failure(line, "Adblock !#endif 没有对应 !#if");
        }
        conditions.pop();
    }

    private boolean isActive() {
        return conditions.isEmpty() || conditions.peek().active();
    }

    private static String unquote(String value, SourceLine line) {
        if (value.length() >= 2 && (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                || value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) {
            throw failure(line, "Adblock include 路径为空");
        }
        return value;
    }

    private static RuleProcessingException failure(SourceLine line, String message) {
        return failure(line.source() + ":" + line.lineNumber(), message);
    }

    private static RuleProcessingException failure(String source, String message) {
        return new RuleProcessingException(message + ": source=" + source);
    }

    private record ConditionalFrame(boolean parentActive, boolean condition, boolean elseSeen, boolean active) {
    }
}

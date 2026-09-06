package dev.fordes.adfs.format;

import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import dev.fordes.adfs.config.EffectiveConfig.InputLimits;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.rule.spool.RuleSpool;
import dev.fordes.adfs.source.BoundedLineReader;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;

@Slf4j
public final class TextSource {

    private static final char UTF8_BOM = '\ufeff';

    private TextSource() {
    }

    public static void read(SourceSession session, InputLimits limits, Consumer<SourceLine> consumer) {
        try (BoundedLineReader reader = new BoundedLineReader(session.root(), limits.maxLineLength())) {
            boolean first = true;
            Optional<SourceLine> next;
            while ((next = reader.readLine()).isPresent()) {
                SourceLine line = next.orElseThrow();
                if (first && !line.text().isEmpty() && line.text().charAt(0) == UTF8_BOM) {
                    line = new SourceLine(line.source(), line.lineNumber(), line.text().substring(1));
                }
                first = false;
                try {
                    if (log.isDebugEnabled()) {
                        MDC.put(RuleSpool.INPUT_RULE, line.text());
                    }
                    consumer.accept(line);
                } catch (RuleProcessingException | IllegalArgumentException exception) {
                    log.debug("规则解析失败:  {} | {} --> {}",
                            MDC.get(RuleSpool.INPUT), line.text(), exception.getMessage());
                    throw new RuleProcessingException("解析文本规则失败: source=" + line.source()
                            + ", line=" + line.lineNumber() + ", reason=" + exception.getMessage(), exception);
                }
            }
        }
    }

    public static String ruleText(SourceLine line, int minimum, int maximum) {
        String text = line.text().strip();
        if (text.length() < minimum || text.length() > maximum) {
            throw new RuleProcessingException("逻辑规则长度越界: source=" + line.source() + ", line="
                    + line.lineNumber() + ", length=" + text.length() + ", allowed=" + minimum + ".." + maximum);
        }
        return text;
    }
}

package org.fordes.adfs.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class LogFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        String output = "%s %s %s - %s%n".formatted(
                TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())),
                displayLevel(record.getLevel()),
                simpleClassName(record.getLoggerName()),
                formatMessage(record)
        );
        if (record.getThrown() == null) {
            return output;
        }
        StringWriter stackTrace = new StringWriter();
        record.getThrown().printStackTrace(new PrintWriter(stackTrace));
        return output + stackTrace;
    }

    private static String simpleClassName(String loggerName) {
        int separator = loggerName.lastIndexOf('.');
        return separator < 0 ? loggerName : loggerName.substring(separator + 1);
    }

    private static String displayLevel(Level level) {
        return switch (level.getName()) {
            case "SEVERE" -> "ERROR";
            case "WARNING" -> "WARN";
            case "INFO" -> "INFO";
            case "FINE" -> "DEBUG";
            case "FINEST" -> "TRACE";
            default -> level.getName();
        };
    }
}

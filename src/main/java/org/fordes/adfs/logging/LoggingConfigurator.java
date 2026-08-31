package org.fordes.adfs.logging;

import org.fordes.adfs.config.BuildPlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class LoggingConfigurator {

    private static final String LOGGER_NAME = "org.fordes.adfs";
    private static final Path LOG_FILE = Path.of("logs/adfs-%g.log").toAbsolutePath().normalize();
    private static final int MAX_SIZE = 10 * 1024 * 1024;
    private static final int MAX_FILES = 1_024;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static Handler handler;

    private LoggingConfigurator() {
    }

    public static synchronized void configure(BuildPlan.LoggingPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy 不能为空");
        Path parent = LOG_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Logger logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false);
        if (handler != null) {
            logger.removeHandler(handler);
            handler.close();
            handler = null;
        }

        FileHandler replacement = new FileHandler(
                LOG_FILE.toString(),
                MAX_SIZE,
                MAX_FILES,
                true
        );
        replacement.setEncoding(StandardCharsets.UTF_8.name());
        replacement.setFormatter(new LogFormatter());
        Level level = toJulLevel(policy.level());
        replacement.setLevel(level);

        logger.setLevel(level);
        logger.addHandler(replacement);
        handler = replacement;
    }

    public static Logger logger(Class<?> source) {
        Objects.requireNonNull(source, "source 不能为空");
        return Logger.getLogger(source.getName());
    }

    public static synchronized void ensureConfigured() throws IOException {
        if (handler == null) {
            configure(BuildPlan.LoggingPolicy.defaults());
        }
    }

    private static Level toJulLevel(BuildPlan.LogLevel level) {
        return switch (level) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
            case OFF -> Level.OFF;
        };
    }

    private static final class LogFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            String sourceClass = record.getSourceClassName() == null
                    ? record.getLoggerName()
                    : record.getSourceClassName();
            StringBuilder output = new StringBuilder()
                    .append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(displayLevel(record.getLevel()))
                    .append(' ')
                    .append(simpleClassName(sourceClass))
                    .append(" - ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter stackTrace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stackTrace));
                output.append(stackTrace);
            }
            return output.toString();
        }

        private static String simpleClassName(String sourceClass) {
            int separator = sourceClass.lastIndexOf('.');
            return separator < 0 ? sourceClass : sourceClass.substring(separator + 1);
        }

        private static String displayLevel(Level level) {
            if (level == Level.SEVERE) {
                return "ERROR";
            }
            if (level == Level.WARNING) {
                return "WARN";
            }
            if (level == Level.INFO) {
                return "INFO";
            }
            if (level == Level.FINE) {
                return "DEBUG";
            }
            if (level == Level.FINEST) {
                return "TRACE";
            }
            return level.getName();
        }
    }

}

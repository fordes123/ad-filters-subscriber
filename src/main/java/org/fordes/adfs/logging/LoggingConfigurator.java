package org.fordes.adfs.logging;

import org.fordes.adfs.config.BuildPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

/**
 * 配置 SLF4J 的 JDK 日志后端。应用日志只写入文件，不干扰命令行输出。
 */
public final class LoggingConfigurator {

    private static final String LOGGER_NAME = "org.fordes.adfs";
    private static final Path LOG_FILE = Path.of("logs/adfs-%g.log").toAbsolutePath().normalize();
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 10;

    private static Handler handler;

    private LoggingConfigurator() {
    }

    public static synchronized void configure(BuildPlan.LoggingPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy 不能为空");
        Path parent = LOG_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        java.util.logging.Logger backend = java.util.logging.Logger.getLogger(LOGGER_NAME);
        backend.setUseParentHandlers(false);
        if (handler != null) {
            backend.removeHandler(handler);
            handler.close();
        }

        Level level = toJulLevel(policy.level());
        FileHandler replacement = new FileHandler(
                LOG_FILE.toString(), MAX_FILE_BYTES, MAX_FILE_COUNT, true);
        replacement.setEncoding(StandardCharsets.UTF_8.name());
        replacement.setFormatter(new LogFormatter());
        replacement.setLevel(level);
        backend.setLevel(level);
        backend.addHandler(replacement);
        handler = replacement;
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
}

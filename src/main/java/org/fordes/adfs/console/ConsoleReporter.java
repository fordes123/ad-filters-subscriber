package org.fordes.adfs.console;

import picocli.CommandLine.Help.Ansi;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Objects;

public final class ConsoleReporter {

    private final PrintWriter writer;
    private final Ansi ansi;
    private final boolean enabled;

    public ConsoleReporter(PrintWriter writer, Ansi ansi) {
        this(writer, ansi, true);
    }

    private ConsoleReporter(PrintWriter writer, Ansi ansi, boolean enabled) {
        this.writer = Objects.requireNonNull(writer, "writer 不能为空");
        this.ansi = Objects.requireNonNull(ansi, "ansi 不能为空");
        this.enabled = enabled;
    }

    public void status(String message) {
        write("@|cyan,bold ›|@", message);
    }

    public void warning(String message) {
        write("@|yellow,bold !|@", message);
    }

    public void failure(String message, Throwable error) {
        Objects.requireNonNull(error, "error 不能为空");
        Throwable root = rootCause(error);
        String reason = root.getMessage();
        write(
                "@|red,bold ×|@",
                message + ": " + (reason == null || reason.isBlank()
                        ? root.getClass().getSimpleName()
                        : reason)
        );
    }

    public static ConsoleReporter silent() {
        return new ConsoleReporter(new PrintWriter(Writer.nullWriter()), Ansi.OFF, false);
    }

    private void write(String marker, String message) {
        Objects.requireNonNull(message, "message 不能为空");
        if (!enabled) {
            return;
        }
        synchronized (writer) {
            writer.printf("%s %s%n", ansi.string(marker), message);
            writer.flush();
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}

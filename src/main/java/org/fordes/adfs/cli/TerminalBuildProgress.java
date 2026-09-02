package org.fordes.adfs.cli;

import org.fordes.adfs.engine.BuildProgressListener;
import picocli.CommandLine.Help.Ansi;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class TerminalBuildProgress implements BuildProgressListener, AutoCloseable {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final long REFRESH_MILLIS = 120;

    private final PrintWriter out;
    private final Ansi ansi;
    private final boolean interactive;
    private final ScheduledExecutorService renderer;

    private Stage stage;
    private String item;
    private long completed;
    private long total;
    private long processed;
    private long stageStartedAt;
    private int spinnerIndex;
    private boolean active;
    private boolean closed;

    TerminalBuildProgress(PrintWriter out, Ansi ansi) {
        this.out = Objects.requireNonNull(out, "out 不能为空");
        this.ansi = Objects.requireNonNull(ansi, "ansi 不能为空");
        this.interactive = System.console() != null && ansi.enabled();
        this.renderer = interactive
                ? Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "adfs-progress");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        if (renderer != null) {
            renderer.scheduleAtFixedRate(
                    this::render,
                    0,
                    REFRESH_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public synchronized void stageStarted(Stage stage, long total) {
        ensureOpen();
        this.stage = Objects.requireNonNull(stage, "stage 不能为空");
        this.item = null;
        this.completed = 0;
        this.total = total;
        this.processed = 0;
        this.stageStartedAt = System.nanoTime();
        this.active = true;
        if (!interactive) {
            out.printf("→ %s%n", stage.description());
            out.flush();
        }
    }

    @Override
    public synchronized void stageAdvanced(
            Stage stage,
            String item,
            long completed,
            long total,
            long processed
    ) {
        ensureActiveStage(stage);
        this.item = item;
        this.completed = completed;
        this.total = total;
        this.processed = processed;
        if (!interactive && item != null) {
            out.printf("  完成 %s（%,d/%,d）%n", item, completed, total);
            out.flush();
        }
    }

    @Override
    public synchronized void stageCompleted(
            Stage stage,
            long completed,
            long total,
            long processed
    ) {
        ensureActiveStage(stage);
        this.completed = completed;
        this.total = total;
        this.processed = processed;
        if (interactive) {
            clearLine();
        }
        out.printf(
                "%s %s%s · %s%n",
                ansi.string("@|green,bold ✓|@"),
                stage.description(),
                formatMetrics(stage, completed, total, processed),
                formatDuration(elapsed())
        );
        out.flush();
        active = false;
    }

    private synchronized void render() {
        if (closed || !active) {
            return;
        }
        String spinner = SPINNER[spinnerIndex++ % SPINNER.length];
        String currentItem = item == null ? "" : " · 最近完成 " + item;
        clearLine();
        out.printf(
                "%s %s%s%s · %s",
                spinner,
                stage.description(),
                formatMetrics(stage, completed, total, processed),
                currentItem,
                formatDuration(elapsed())
        );
        out.flush();
    }

    private static String formatMetrics(
            Stage stage,
            long completed,
            long total,
            long processed
    ) {
        return switch (stage) {
            case SOURCES -> String.format(
                    Locale.ROOT,
                    "  %,d/%,d 完成 · %,d 条规则",
                    completed,
                    total,
                    processed
            );
            case DNS_VALIDATION -> String.format(
                    Locale.ROOT,
                    "  已检查 %,d 个域名",
                    processed
            );
            case OUTPUTS -> String.format(
                    Locale.ROOT,
                    "  %,d/%,d 完成",
                    completed,
                    total
            );
        };
    }

    private Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - stageStartedAt);
    }

    private static String formatDuration(Duration duration) {
        if (duration.toMillis() < 1_000) {
            return duration.toMillis() + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1_000.0);
    }

    private void clearLine() {
        out.print("\r\u001B[2K");
    }

    private void ensureActiveStage(Stage expected) {
        ensureOpen();
        if (!active || stage != expected) {
            throw new IllegalStateException("进度阶段不匹配: expected=" + expected + ", actual=" + stage);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("构建进度输出已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (renderer != null) {
            renderer.shutdownNow();
        }
        if (interactive && active) {
            clearLine();
            out.flush();
        }
        active = false;
    }
}

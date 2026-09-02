package org.fordes.adfs.cli;

import org.fordes.adfs.engine.BuildReport;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.ColorScheme;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class BuildResultPrinter {

    private final PrintWriter out;
    private final Ansi ansi;

    BuildResultPrinter(PrintWriter out, ColorScheme colorScheme) {
        this.out = out;
        this.ansi = colorScheme.ansi();
    }

    void print(BuildReport report) {
        long rules = report.sources().stream().mapToLong(BuildReport.Source::parsed).sum();
        long invalid = report.invalidRules();
        long approximations = report.outputs().stream()
                .mapToLong(BuildReport.Output::approximations)
                .sum();
        long unsupported = report.outputs().stream()
                .mapToLong(BuildReport.Output::unsupported)
                .sum();
        long emptySources = report.sources().stream()
                .filter(source -> source.parsed() == 0)
                .count();
        long emptyOutputs = report.outputs().stream()
                .filter(output -> output.finalRules() == 0)
                .count();

        out.printf(
                "%n%s  %,d 个源 → %,d 条规则 → %,d 个文件  %s%n%n",
                ansi.string("@|green,bold ✓ 完成|@"),
                report.sources().size(),
                rules,
                report.outputs().size(),
                formatDuration(report.elapsed())
        );
        Path outputDirectory = commonDirectory(report.outputs());
        printFiles(report.outputs(), outputDirectory == null);
        printOutputDirectory(outputDirectory);
        printWarning(invalid, approximations, unsupported, emptySources, emptyOutputs);
        out.flush();
    }

    private void printFiles(List<BuildReport.Output> outputs, boolean showFullPath) {
        int nameWidth = outputs.stream()
                .mapToInt(output -> displayWidth(label(output, showFullPath)))
                .max()
                .orElse(0);
        int rulesWidth = outputs.stream()
                .mapToInt(output -> displayWidth(metric("规则", output.finalRules())))
                .max()
                .orElse(0);
        int approximationWidth = outputs.stream()
                .mapToInt(output -> displayWidth(metric("近似", output.approximations())))
                .max()
                .orElse(0);
        int unsupportedWidth = outputs.stream()
                .mapToInt(output -> displayWidth(metric("无法转换", output.unsupported())))
                .max()
                .orElse(0);
        for (BuildReport.Output output : outputs) {
            out.printf(
                    "  %s  %s  %s  %s%n",
                    padRight(label(output, showFullPath), nameWidth),
                    padRight(metric("规则", output.finalRules()), rulesWidth),
                    padRight(metric("近似", output.approximations()), approximationWidth),
                    padRight(metric("无法转换", output.unsupported()), unsupportedWidth)
            );
        }
    }

    private static String metric(String label, long value) {
        return label + " " + String.format(Locale.ROOT, "%,d", value);
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(width - displayWidth(value));
    }

    private void printOutputDirectory(Path outputDirectory) {
        if (outputDirectory != null) {
            out.printf("%n  %-4s %s%n", "输出", outputDirectory);
        }
    }

    private static Path commonDirectory(List<BuildReport.Output> outputs) {
        Path parent = outputs.getFirst().path().getParent();
        return outputs.stream().allMatch(output -> parent.equals(output.path().getParent()))
                ? parent
                : null;
    }

    private static String label(BuildReport.Output output, boolean showFullPath) {
        return showFullPath
                ? output.path().toString()
                : output.path().getFileName().toString();
    }

    private static int displayWidth(String value) {
        return value.codePoints().map(character -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(character);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    ? 2
                    : 1;
        }).sum();
    }

    private void printWarning(
            long invalid,
            long approximations,
            long unsupported,
            long emptySources,
            long emptyOutputs
    ) {
        StringBuilder warning = new StringBuilder();
        appendMetric(warning, "无效规则", invalid);
        appendMetric(warning, "近似", approximations);
        appendMetric(warning, "无法转换", unsupported);
        appendMetric(warning, "空规则源", emptySources);
        appendMetric(warning, "空文件", emptyOutputs);
        if (!warning.isEmpty()) {
            out.printf(
                    "%n%s  %s%n",
                    ansi.string("@|yellow,bold ! 注意|@"),
                    warning
            );
        }
    }

    private static void appendMetric(StringBuilder output, String label, long value) {
        if (value == 0) {
            return;
        }
        if (!output.isEmpty()) {
            output.append(" · ");
        }
        output.append(label)
                .append(' ')
                .append(String.format(Locale.ROOT, "%,d", value));
    }

    private static String formatDuration(Duration duration) {
        if (duration.toMillis() < 1_000) {
            return duration.toMillis() + " ms";
        }
        return String.format(Locale.ROOT, "%.2f s", duration.toMillis() / 1_000.0);
    }
}

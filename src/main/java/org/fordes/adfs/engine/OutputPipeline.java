package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.tracking.ConversionTracker;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class OutputPipeline implements Callable<OutputPipeline.Staged> {

    private static final DateTimeFormatter HEADER_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int QUEUE_CAPACITY = 4;
    private static final int WRITE_BUFFER_SIZE = 256 * 1024;

    private final BuildPlan plan;
    private final BuildPlan.OutputSpec output;
    private final BuildWorkspace workspace;
    private final RuleEncoder encoder;
    private final Path logSegment;
    private final Queue<Path> temporaryPaths;
    private final AtomicReference<Throwable> failure;
    private final long memoryBudget;
    private final ArrayBlockingQueue<Batch> queue;

    OutputPipeline(
            BuildPlan plan,
            BuildPlan.OutputSpec output,
            BuildWorkspace workspace,
            RuleEncoder encoder,
            Path logSegment,
            Queue<Path> temporaryPaths,
            AtomicReference<Throwable> failure,
            long memoryBudget
    ) {
        this.plan = Objects.requireNonNull(plan, "plan 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.encoder = Objects.requireNonNull(encoder, "encoder 不能为空");
        this.logSegment = Objects.requireNonNull(logSegment, "logSegment 不能为空");
        this.temporaryPaths = Objects.requireNonNull(temporaryPaths, "temporaryPaths 不能为空");
        this.failure = Objects.requireNonNull(failure, "failure 不能为空");
        this.memoryBudget = memoryBudget;
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    }

    boolean accepts(String sourceId) {
        return output.sources().isEmpty() || output.sources().contains(sourceId);
    }

    void enqueue(List<IndexedRule> rules) throws IOException, InterruptedException {
        enqueue(new Batch(rules, false));
    }

    void finishInput() throws IOException, InterruptedException {
        enqueue(new Batch(List.of(), true));
    }

    @Override
    public Staged call() throws Exception {
        Path temporary = null;
        try (ConversionTracker tracker = ConversionTracker.open(logSegment);
             SpillableRuleDeduplicator deduplicator =
                     new SpillableRuleDeduplicator(workspace, memoryBudget)) {
            Counters counters = consume(deduplicator, tracker);
            SpillableRuleDeduplicator.Result result = deduplicator.finish();
            temporary = createTemporaryOutput();
            temporaryPaths.add(temporary);
            writeArtifact(temporary, result, tracker);
            return new Staged(
                    temporary,
                    logSegment,
                    new BuildEngine.OutputReport(
                            output.path(),
                            counters.approximations,
                            counters.unsupported,
                            result.unique()
                    )
            );
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                    temporaryPaths.remove(temporary);
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        }
    }

    private Counters consume(
            SpillableRuleDeduplicator deduplicator,
            ConversionTracker tracker
    ) throws IOException, InterruptedException {
        Counters counters = new Counters();
        String outputName = output.path().getFileName().toString();
        while (true) {
            Batch batch = queue.take();
            if (batch.end()) {
                return counters;
            }
            for (IndexedRule indexed : batch.rules()) {
                RuleRecord record = indexed.rule();
                RuleEncoder.ConversionResult conversion = encoder.encode(
                        record,
                        output,
                        plan.processing().allowNarrowing(),
                        plan.processing().allowBroadening()
                );
                switch (conversion.status()) {
                    case EXACT -> {
                    }
                    case NARROWING, BROADENING -> counters.approximations++;
                    case UNSUPPORTED -> counters.unsupported++;
                }
                if (conversion.content().isPresent()) {
                    deduplicator.add(
                            indexed.sequence(),
                            conversion.content().orElseThrow(),
                            record
                    );
                } else {
                    tracker.failure(
                            record.sourceId(),
                            outputName,
                            record.raw(),
                            conversion.reason()
                    );
                }
            }
        }
    }

    private void enqueue(Batch batch) throws IOException, InterruptedException {
        while (!queue.offer(batch, 100, TimeUnit.MILLISECONDS)) {
            throwWorkerFailure();
        }
    }

    private void throwWorkerFailure() throws IOException, InterruptedException {
        Throwable cause = failure.get();
        if (cause == null) {
            return;
        }
        if (cause instanceof IOException ioError) {
            throw ioError;
        }
        if (cause instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IOException("输出工作线程失败: " + cause.getMessage(), cause);
    }

    private Path createTemporaryOutput() throws IOException {
        Path target = output.path();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        String prefix = target.getFileName().toString();
        if (prefix.length() < 3) {
            prefix = "adfs-" + prefix;
        }
        return Files.createTempFile(parent, prefix + ".", ".tmp");
    }

    private void writeArtifact(
            Path temporary,
            SpillableRuleDeduplicator.Result rules,
            ConversionTracker tracker
    ) throws IOException {
        try (BufferedWriter writer = openWriter(temporary)) {
            if (output.format() == RuleFormat.SING_BOX) {
                writeSingBoxArtifact(writer, rules, tracker);
                return;
            }
            writeHeader(writer, rules.unique());
            String outputName = output.path().getFileName().toString();
            rules.forEach(candidate -> {
                writer.write(candidate.content());
                writer.newLine();
                logSuccess(tracker, outputName, candidate);
            });
        }
    }

    private void writeHeader(BufferedWriter writer, long total) throws IOException {
        String header = renderHeader(total);
        if (!header.isBlank()) {
            String prefix = encoder.headerPrefix(output.format());
            for (String headerLine : header.lines().toList()) {
                if (!headerLine.isBlank()) {
                    writer.write(prefix);
                    writer.write(headerLine.strip());
                    writer.newLine();
                }
            }
        }
        var fixedHeader = encoder.fixedHeader(output.format());
        if (fixedHeader.isPresent()) {
            writer.write(fixedHeader.orElseThrow());
            writer.newLine();
        }
    }

    private void writeSingBoxArtifact(
            BufferedWriter writer,
            SpillableRuleDeduplicator.Result rules,
            ConversionTracker tracker
    ) throws IOException {
        String outputName = output.path().getFileName().toString();
        writer.write("{\n  \"version\": 2,\n  \"rules\": [");
        if (rules.unique() > 0) {
            writer.newLine();
            long[] index = {0};
            rules.forEach(candidate -> {
                writer.write(candidate.content());
                if (++index[0] < rules.unique()) {
                    writer.write(',');
                }
                writer.newLine();
                logSuccess(tracker, outputName, candidate);
            });
            writer.write("  ");
        }
        writer.write("]\n}");
        writer.newLine();
    }

    private void logSuccess(
            ConversionTracker tracker,
            String outputName,
            SpillableRuleDeduplicator.Candidate candidate
    ) throws IOException {
        if (!plan.logging().includeSuccessfulConversions()) {
            return;
        }
        tracker.success(
                candidate.sourceId(),
                outputName,
                candidate.raw(),
                candidate.content()
        );
    }

    private String renderHeader(long total) {
        return output.header()
                .replace("${date}", LocalDateTime.now().format(HEADER_DATE))
                .replace("${name}", output.path().getFileName().toString())
                .replace(
                        "${type}",
                        output.format().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                )
                .replace("${desc}", output.description())
                .replace("${total}", Long.toString(total));
    }

    private static BufferedWriter openWriter(Path path) throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(
                        new BufferedOutputStream(
                                Files.newOutputStream(
                                        path,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE
                                ),
                                WRITE_BUFFER_SIZE
                        ),
                        StandardCharsets.UTF_8
                ),
                WRITE_BUFFER_SIZE
        );
    }

    record IndexedRule(long sequence, RuleRecord rule) {
    }

    record Staged(Path temporaryPath, Path logSegment, BuildEngine.OutputReport report) {
    }

    private record Batch(List<IndexedRule> rules, boolean end) {

        private Batch {
            rules = List.copyOf(rules);
        }
    }

    private static final class Counters {
        private long approximations;
        private long unsupported;
    }
}

package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.logging.RuleLogContext;
import org.fordes.adfs.model.RuleRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为一个目标文件完成规则转换、去重和写入。
 */
final class OutputPipeline implements Callable<BuildReport.Output> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputPipeline.class);
    private static final DateTimeFormatter HEADER_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int QUEUE_CAPACITY = 4;

    private final BuildPlan.ProcessingPolicy processing;
    private final BuildPlan.OutputSpec output;
    private final Path stagedOutput;
    private final BuildWorkspace workspace;
    private final RuleEncoder encoder;
    private final AtomicReference<Throwable> failure;
    private final long memoryBudget;
    private final ArrayBlockingQueue<Batch> queue;
    private final ArtifactWriter artifactWriter;

    OutputPipeline(
            BuildPlan.ProcessingPolicy processing,
            BuildPlan.OutputSpec output,
            Path stagedOutput,
            BuildWorkspace workspace,
            RuleEncoder encoder,
            AtomicReference<Throwable> failure,
            long memoryBudget
    ) {
        this.processing = Objects.requireNonNull(processing, "processing 不能为空");
        this.output = Objects.requireNonNull(output, "output 不能为空");
        this.stagedOutput = Objects.requireNonNull(stagedOutput, "stagedOutput 不能为空");
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.encoder = Objects.requireNonNull(encoder, "encoder 不能为空");
        this.failure = Objects.requireNonNull(failure, "failure 不能为空");
        this.memoryBudget = memoryBudget;
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.artifactWriter = new ArtifactWriter(output);
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
    public BuildReport.Output call() throws Exception {
        try (SpillableRuleDeduplicator deduplicator =
                     new SpillableRuleDeduplicator(workspace, memoryBudget)) {
            OutputCounters counters = consume(deduplicator);
            SpillableRuleDeduplicator.Result result = deduplicator.finish();
            artifactWriter.write(stagedOutput, result, renderHeader(result.unique()));
            return new BuildReport.Output(
                    output.path(),
                    counters.approximations(),
                    counters.unsupported(),
                    result.unique()
            );
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            throw error;
        }
    }

    private OutputCounters consume(SpillableRuleDeduplicator deduplicator)
            throws IOException, InterruptedException {
        long approximations = 0;
        long unsupported = 0;
        while (true) {
            Batch batch = queue.take();
            if (batch.end()) {
                return new OutputCounters(approximations, unsupported);
            }
            for (IndexedRule indexed : batch.rules()) {
                RuleEncoder.ConversionResult conversion = encoder.encode(
                        indexed.rule(),
                        output,
                        processing.allowNarrowing(),
                        processing.allowBroadening()
                );
                switch (conversion.status()) {
                    case EXACT -> addConverted(
                            deduplicator,
                            indexed,
                            conversion.content().orElseThrow()
                    );
                    case NARROWING, BROADENING -> {
                        approximations++;
                        addConverted(
                                deduplicator,
                                indexed,
                                conversion.content().orElseThrow()
                        );
                    }
                    case UNSUPPORTED -> {
                        unsupported++;
                        if (LOGGER.isDebugEnabled()) {
                            logFailure(indexed.rule(), conversion.reason());
                        }
                    }
                }
            }
        }
    }

    private void addConverted(
            SpillableRuleDeduplicator deduplicator,
            IndexedRule indexed,
            String content
    ) throws IOException {
        deduplicator.add(indexed.sequence(), content, content);
        logSuccess(indexed.rule(), content);
    }

    private void logSuccess(RuleRecord record, String content) {
        if (!LOGGER.isTraceEnabled()) {
            return;
        }
        LOGGER.trace(
                "规则转换成功, {} --> {}: {} --> {}",
                RuleLogContext.source(record),
                RuleLogContext.output(output),
                record.raw(),
                content
        );
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

    private void logFailure(RuleRecord record, String reason) {
        LOGGER.debug(
                "规则转换失败, {} --> {}: {} --> {}",
                RuleLogContext.source(record),
                RuleLogContext.output(output),
                record.raw(),
                reason
        );
    }

    private String renderHeader(long total) {
        return output.header()
                .replace("${date}", LocalDateTime.now().format(HEADER_DATE))
                .replace("${name}", output.path().getFileName().toString())
                .replace("${type}", output.format().name)
                .replace("${desc}", output.description())
                .replace("${total}", Long.toString(total));
    }

    record IndexedRule(long sequence, RuleRecord rule) {

        IndexedRule {
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence 不能小于 0");
            }
            Objects.requireNonNull(rule, "rule 不能为空");
        }
    }

    private record Batch(List<IndexedRule> rules, boolean end) {

        private Batch {
            rules = List.copyOf(rules);
        }
    }

    private record OutputCounters(long approximations, long unsupported) {
    }
}

package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.logging.LoggingConfigurator;
import org.fordes.adfs.logging.RuleLogContext;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.preprocess.AdblockPreprocessor;
import org.fordes.adfs.preprocess.PreprocessResult;
import org.fordes.adfs.preprocess.PreprocessedLine;
import org.fordes.adfs.preprocess.PreprocessorDiagnostic;
import org.fordes.adfs.source.SourceOpener;
import org.fordes.adfs.source.StreamingLineReader;
import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.RuleFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BuildEngine {

    private static final int RULE_BATCH_SIZE = 1_024;
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildEngine.class);
    private final RuleParser parser;
    private final RuleEncoder encoder;
    private final SingBoxStreamingDecoder singBoxDecoder;

    public BuildEngine() {
        this.parser = new RuleParser();
        this.encoder = new RuleEncoder();
        this.singBoxDecoder = new SingBoxStreamingDecoder();
    }

    public BuildReport build(BuildPlan plan) throws IOException, InterruptedException {
        return build(plan, NoOpBuildProgressListener.INSTANCE);
    }

    public BuildReport build(BuildPlan plan, BuildProgressListener progress)
            throws IOException, InterruptedException {
        Objects.requireNonNull(plan, "plan 不能为空");
        Objects.requireNonNull(progress, "progress 不能为空");
        long startedAt = System.nanoTime();
        LoggingConfigurator.configure(plan.logging());
        try (BuildWorkspace workspace = BuildWorkspace.create()) {
            List<BuildPlan.SourceSpec> orderedSources = plan.sources().stream()
                    .sorted(Comparator.comparingInt(BuildPlan.SourceSpec::priority)
                            .reversed()
                            .thenComparing(BuildPlan.SourceSpec::id))
                    .toList();
            progress.stageStarted(BuildProgressListener.Stage.SOURCES, orderedSources.size());
            List<SourceStage> sourceStages = parseSources(
                    plan, orderedSources, workspace, progress);
            long parsedRules = sourceStages.stream()
                    .map(SourceStage::report)
                    .mapToLong(BuildReport.Source::parsed)
                    .sum();
            progress.stageCompleted(
                    BuildProgressListener.Stage.SOURCES,
                    orderedSources.size(),
                    orderedSources.size(),
                    parsedRules
            );

            if (plan.processing().dnsValidation().enabled()) {
                progress.stageStarted(BuildProgressListener.Stage.DNS_VALIDATION, 0);
                AtomicLong validatedDomains = new AtomicLong();
                sourceStages = new DnsValidationPipeline(
                        plan.processing().dnsValidation(),
                        workspace,
                        processed -> {
                            validatedDomains.set(processed);
                            progress.stageAdvanced(
                                    BuildProgressListener.Stage.DNS_VALIDATION,
                                    null,
                                    0,
                                    0,
                                    processed
                            );
                        }
                ).validate(sourceStages);
                progress.stageCompleted(
                        BuildProgressListener.Stage.DNS_VALIDATION,
                        0,
                        0,
                        validatedDomains.get()
                );
            }

            progress.stageStarted(BuildProgressListener.Stage.OUTPUTS, plan.outputs().size());
            List<BuildReport.Output> outputReports = writeOutputs(
                    plan, sourceStages, workspace, progress);
            progress.stageCompleted(
                    BuildProgressListener.Stage.OUTPUTS,
                    plan.outputs().size(),
                    plan.outputs().size(),
                    0
            );
            return new BuildReport(
                    sourceStages.stream().map(SourceStage::report).toList(),
                    outputReports,
                    Duration.ofNanos(System.nanoTime() - startedAt)
            );
        }
    }

    private List<SourceStage> parseSources(
            BuildPlan plan,
            List<BuildPlan.SourceSpec> sources,
            BuildWorkspace workspace,
            BuildProgressListener progress
    ) throws IOException, InterruptedException {
        SourceOpener opener = new SourceOpener(plan.sourceLoading());
        AtomicLong completed = new AtomicLong();
        AtomicLong parsed = new AtomicLong();
        try (ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentTasks(sources.size()))) {
            List<Future<SourceStage>> futures = new ArrayList<>(sources.size());
            for (BuildPlan.SourceSpec source : sources) {
                futures.add(executor.submit(() -> {
                    SourceStage result = parseSource(plan, opener, source, workspace);
                    long parsedRules = parsed.addAndGet(result.report().parsed());
                    long completedSources = completed.incrementAndGet();
                    progress.stageAdvanced(
                            BuildProgressListener.Stage.SOURCES,
                            source.id(),
                            completedSources,
                            sources.size(),
                            parsedRules
                    );
                    return result;
                }));
            }
            return await(futures, "规则源解析失败");
        }
    }

    private SourceStage parseSource(
            BuildPlan plan,
            SourceOpener opener,
            BuildPlan.SourceSpec source,
            BuildWorkspace workspace
    ) throws IOException, InterruptedException {
        Path segment = workspace.createFile("source", ".segment");
        if (source.format() == RuleFormat.SING_BOX) {
            return parseSingBoxSource(plan, opener, source, segment);
        }
        try (RuleSegment.Writer writer = RuleSegment.writer(segment, source)) {
            SourceAccumulator accumulator = new SourceAccumulator(source.id(), writer);
            AdblockPreprocessor preprocessor = (source.format() == RuleFormat.EASYLIST
                    || source.format() == RuleFormat.DNS)
                    ? new AdblockPreprocessor(source.dialect())
                    : null;
            try (SourceOpener.OpenedSource opened = opener.open(source)) {
                if (opened.charset().equals(StandardCharsets.UTF_8)) {
                    new StreamingLineReader().read(opened.input(), opened.bufferSize(), line -> acceptLine(
                            plan, source, preprocessor, line, accumulator));
                } else {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(opened.input(), opened.charset()), opened.bufferSize())) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            acceptLine(plan, source, preprocessor, LineSlice.fromUtf8(line), accumulator);
                        }
                    }
                }
            }
            if (preprocessor != null) {
                for (PreprocessorDiagnostic diagnostic : preprocessor.finish()) {
                    markInvalid(
                            source,
                            "<规则源结束>",
                            diagnostic.code(),
                            diagnostic.message(),
                            accumulator
                    );
                }
            }
            return new SourceStage(source, segment, accumulator.finish());
        } catch (IOException | InterruptedException | RuntimeException error) {
            try {
                Files.deleteIfExists(segment);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private SourceStage parseSingBoxSource(
            BuildPlan plan,
            SourceOpener opener,
            BuildPlan.SourceSpec source,
            Path segment
    ) throws IOException, InterruptedException {
        SingBoxStreamingDecoder.Result result;
        BuildReport.Source report;
        try (RuleSegment.Writer writer = RuleSegment.writer(segment, source);
             SourceOpener.OpenedSource opened = opener.open(source)) {
            SourceAccumulator accumulator = new SourceAccumulator(source.id(), writer);
            accumulator.nextRawLine();
            result = singBoxDecoder.decode(
                    opened.input(),
                    opened.charset(),
                    source,
                    rule -> {
                        if (!excluded(plan.processing().excludedDomains(), rule)) {
                            accumulator.parsed(rule);
                        }
                    }
            );
            if (result.issue().isEmpty()) {
                return new SourceStage(source, segment, accumulator.finish());
            }
            RuleParser.ParseIssue issue = result.issue().orElseThrow();
            logInvalidRule(source, "<sing-box 规则集>", issue.code(), issue.message());
            report = new BuildReport.Source(source.id(), 0, 1);
        } catch (IOException | InterruptedException | RuntimeException error) {
            try {
                Files.deleteIfExists(segment);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        Files.deleteIfExists(segment);
        try (RuleSegment.Writer ignored = RuleSegment.writer(segment, source)) {
            return new SourceStage(source, segment, report);
        }
    }

    private void acceptLine(
            BuildPlan plan,
            BuildPlan.SourceSpec source,
            AdblockPreprocessor preprocessor,
            LineSlice line,
            SourceAccumulator accumulator
    ) throws IOException {
        long physicalLine = accumulator.nextRawLine();
        if (!withinLength(plan.processing(), line.length())) {
            markInvalid(
                    source,
                    line.materialize(),
                    "RULE_LENGTH_OUT_OF_RANGE",
                    "规则长度不符合配置限制",
                    accumulator
            );
            return;
        }
        if (preprocessor == null) {
            String raw = line.materialize();
            acceptOutcome(
                    plan,
                    source,
                    line,
                    parser.parseText(source, raw),
                    accumulator
            );
            return;
        }
        PreprocessResult result = preprocessor.process(line, physicalLine);
        if (!result.diagnostics().isEmpty()) {
            String raw = line.materialize();
            for (PreprocessorDiagnostic diagnostic : result.diagnostics()) {
                markInvalid(
                        source,
                        raw,
                        diagnostic.code(),
                        diagnostic.message(),
                        accumulator
                );
            }
        }
        for (PreprocessedLine logicalLine : result.logicalLines()) {
            if (!withinLength(plan.processing(), logicalLine.line().length())) {
                markInvalid(
                        source,
                        logicalLine.line().materialize(),
                        "RULE_LENGTH_OUT_OF_RANGE",
                        "规则长度不符合配置限制",
                        accumulator
                );
                continue;
            }
            RuleParser.ParseOutcome outcome = source.format() == RuleFormat.EASYLIST
                    ? parser.parseAdblock(source, logicalLine.line())
                    : parser.parseText(source, logicalLine.line().materialize());
            acceptOutcome(
                    plan,
                    source,
                    logicalLine.line(),
                    outcome,
                    accumulator
            );
        }
    }

    private static void acceptOutcome(
            BuildPlan plan,
            BuildPlan.SourceSpec source,
            LineSlice line,
            RuleParser.ParseOutcome outcome,
            SourceAccumulator accumulator
    ) throws IOException {
        if (outcome.issue().isPresent()) {
            RuleParser.ParseIssue issue = outcome.issue().orElseThrow();
            markInvalid(
                    source,
                    line.materialize(),
                    issue.code(),
                    issue.message(),
                    accumulator
            );
            return;
        }
        if (outcome.rules().isEmpty()) {
            return;
        }
        for (RuleRecord rule : outcome.rules()) {
            if (!excluded(plan.processing().excludedDomains(), rule)) {
                accumulator.parsed(rule);
            }
        }
    }

    private static void markInvalid(
            BuildPlan.SourceSpec source,
            String rawRule,
            String code,
            String reason,
            SourceAccumulator accumulator
    ) {
        logInvalidRule(source, rawRule, code, reason);
        accumulator.invalid();
    }

    private static void logInvalidRule(
            BuildPlan.SourceSpec source,
            String rawRule,
            String code,
            String reason
    ) {
        LOGGER.warn(
                "规则解析失败, {}: {} --> {}: {}",
                RuleLogContext.source(source),
                rawRule,
                code,
                reason
        );
    }

    private List<BuildReport.Output> writeOutputs(
            BuildPlan plan,
            List<SourceStage> sourceStages,
            BuildWorkspace workspace,
            BuildProgressListener progress
    ) throws IOException, InterruptedException {
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        long memoryBudget = dedupMemoryBudget(plan.outputs().size());
        List<OutputPipeline> workers = new ArrayList<>(plan.outputs().size());
        List<Path> stagedOutputs = new ArrayList<>(plan.outputs().size());
        for (int index = 0; index < plan.outputs().size(); index++) {
            BuildPlan.OutputSpec output = plan.outputs().get(index);
            Path stagedOutput = workspace.createSiblingFile(output.path(), ".output");
            stagedOutputs.add(stagedOutput);
            workers.add(new OutputPipeline(
                    plan.processing(),
                    output,
                    stagedOutput,
                    workspace,
                    encoder,
                    workerFailure,
                    memoryBudget
            ));
        }

        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentTasks(workers.size()));
        List<Future<BuildReport.Output>> futures = new ArrayList<>(workers.size());
        AtomicLong completed = new AtomicLong();
        try {
            for (int index = 0; index < workers.size(); index++) {
                OutputPipeline worker = workers.get(index);
                BuildPlan.OutputSpec output = plan.outputs().get(index);
                futures.add(executor.submit(() -> {
                    BuildReport.Output result = worker.call();
                    long completedOutputs = completed.incrementAndGet();
                    progress.stageAdvanced(
                            BuildProgressListener.Stage.OUTPUTS,
                            output.path().getFileName().toString(),
                            completedOutputs,
                            workers.size(),
                            0
                    );
                    return result;
                }));
            }
            streamRules(sourceStages, workers, workerFailure);
            for (OutputPipeline worker : workers) {
                worker.finishInput();
            }
            List<BuildReport.Output> reports = await(futures, "输出产物生成失败");
            publishOutputs(plan.outputs(), stagedOutputs, workspace);
            return reports;
        } catch (IOException | InterruptedException | RuntimeException error) {
            executor.shutdownNow();
            throw error;
        } finally {
            executor.close();
        }
    }

    private static void streamRules(
            List<SourceStage> sourceStages,
            List<OutputPipeline> workers,
            AtomicReference<Throwable> workerFailure
    ) throws IOException, InterruptedException {
        long sequence = 0;
        for (SourceStage stage : sourceStages) {
            List<OutputPipeline.IndexedRule> batch = new ArrayList<>(RULE_BATCH_SIZE);
            try (RuleSegment.Reader reader = RuleSegment.reader(stage.segment())) {
                RuleRecord entry;
                while ((entry = reader.read()) != null) {
                    batch.add(new OutputPipeline.IndexedRule(
                            sequence,
                            entry
                    ));
                    sequence++;
                    if (batch.size() == RULE_BATCH_SIZE) {
                        dispatchBatch(stage.report().sourceId(), batch, workers, workerFailure);
                        batch = new ArrayList<>(RULE_BATCH_SIZE);
                    }
                }
            }
            if (!batch.isEmpty()) {
                dispatchBatch(stage.report().sourceId(), batch, workers, workerFailure);
            }
        }
    }

    private static void dispatchBatch(
            String sourceId,
            List<OutputPipeline.IndexedRule> rules,
            List<OutputPipeline> workers,
            AtomicReference<Throwable> workerFailure
    ) throws IOException, InterruptedException {
        List<OutputPipeline.IndexedRule> batch = List.copyOf(rules);
        for (OutputPipeline worker : workers) {
            if (worker.accepts(sourceId)) {
                worker.enqueue(batch);
            }
        }
        throwWorkerFailure(workerFailure);
    }

    private static void publishOutputs(
            List<BuildPlan.OutputSpec> outputs,
            List<Path> stagedOutputs,
            BuildWorkspace workspace
    ) throws IOException {
        List<PublishedOutput> published = new ArrayList<>(outputs.size());
        try {
            for (int index = 0; index < outputs.size(); index++) {
                Path target = outputs.get(index).path();
                Path backup = null;
                if (Files.exists(target)) {
                    if (!Files.isRegularFile(target)) {
                        throw new IOException("输出路径不是普通文件: " + target);
                    }
                    backup = workspace.createSiblingFile(target, ".backup");
                    Files.delete(backup);
                    moveAtomically(target, backup);
                }
                PublishedOutput current = new PublishedOutput(target, backup);
                published.add(current);
                moveAtomically(stagedOutputs.get(index), target);
                current.completed = true;
            }
        } catch (IOException failure) {
            rollbackOutputs(published, failure);
            throw failure;
        }
    }

    private static void rollbackOutputs(List<PublishedOutput> published, IOException failure) {
        for (PublishedOutput output : published.reversed()) {
            try {
                if (output.completed) {
                    Files.deleteIfExists(output.target);
                }
                if (output.backup != null) {
                    moveAtomically(output.backup, output.target);
                }
            } catch (IOException rollbackError) {
                failure.addSuppressed(rollbackError);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("输出文件系统不支持原子替换: " + target, error);
        }
    }

    private static final class PublishedOutput {

        private final Path target;
        private final Path backup;
        private boolean completed;

        private PublishedOutput(Path target, Path backup) {
            this.target = target;
            this.backup = backup;
        }
    }

    private static long dedupMemoryBudget(int outputs) {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long divisor = Math.max(8L, outputs * 4L);
        return Math.clamp(maxHeap / divisor, MEBIBYTE, 128L * MEBIBYTE);
    }

    private static int maxConcurrentTasks(int taskCount) {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.min(taskCount, Math.max(1, processors * 2));
    }

    private static boolean withinLength(BuildPlan.ProcessingPolicy processing, int length) {
        return (processing.minRuleLength() == 0 || length >= processing.minRuleLength())
                && (processing.maxRuleLength() == 0 || length <= processing.maxRuleLength());
    }

    private static boolean excluded(Set<String> excludes, RuleRecord record) {
        return record.body().canonicalRule()
                .filter(rule -> rule.matchType() == CanonicalRule.MatchType.EXACT_DOMAIN
                        || rule.matchType() == CanonicalRule.MatchType.DOMAIN_SUFFIX)
                .map(CanonicalRule::value)
                .map(excludes::contains)
                .orElse(false);
    }

    private static <T> List<T> await(List<Future<T>> futures, String operation)
            throws IOException, InterruptedException {
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException error) {
                throwExecutionCause(error, operation);
            }
        }
        return List.copyOf(results);
    }

    private static void throwExecutionCause(ExecutionException error, String operation)
            throws IOException, InterruptedException {
        Throwable cause = error.getCause();
        if (cause instanceof IOException ioError) {
            throw new IOException(operation, ioError);
        }
        if (cause instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IOException(operation, cause);
    }

    private static void throwWorkerFailure(AtomicReference<Throwable> workerFailure)
            throws IOException, InterruptedException {
        Throwable cause = workerFailure.get();
        if (cause == null) {
            return;
        }
        if (cause instanceof IOException ioError) {
            throw new IOException("输出工作线程失败", ioError);
        }
        if (cause instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IOException("输出工作线程失败", cause);
    }

    private static final class SourceAccumulator {

        private final String sourceId;
        private final RuleSegment.Writer writer;
        private long physicalLine;
        private long parsed;
        private long invalid;

        private SourceAccumulator(String sourceId, RuleSegment.Writer writer) {
            this.sourceId = sourceId;
            this.writer = writer;
        }

        private long nextRawLine() {
            return ++physicalLine;
        }

        private void parsed(RuleRecord rule) throws IOException {
            writer.write(rule);
            parsed++;
        }

        private void invalid() {
            invalid++;
        }

        private BuildReport.Source finish() {
            return new BuildReport.Source(sourceId, parsed, invalid);
        }
    }
}

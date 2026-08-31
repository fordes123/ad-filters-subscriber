package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class BuildEngine {

    private static final int RULE_BATCH_SIZE = 1_024;
    private static final long MEBIBYTE = 1024L * 1024L;
    private final RuleParser parser;
    private final RuleEncoder encoder;
    private final SingBoxStreamingDecoder singBoxDecoder;

    public BuildEngine() {
        this.parser = new RuleParser();
        this.encoder = new RuleEncoder();
        this.singBoxDecoder = new SingBoxStreamingDecoder();
    }

    public BuildReport build(BuildPlan plan) throws IOException, InterruptedException {
        Objects.requireNonNull(plan, "plan 不能为空");
        long startedAt = System.nanoTime();
        try (BuildWorkspace workspace = BuildWorkspace.create()) {
            List<BuildPlan.SourceSpec> orderedSources = plan.sources().stream()
                    .sorted(Comparator.comparingInt(BuildPlan.SourceSpec::priority)
                            .reversed()
                            .thenComparing(BuildPlan.SourceSpec::id))
                    .toList();
            List<SourceStage> sourceStages = parseSources(plan, orderedSources, workspace);
            sourceStages = new DnsValidationPipeline(
                    plan.processing().dnsValidation(),
                    workspace
            ).validate(sourceStages);
            List<OutputPipeline.Staged> stagedOutputs = stageOutputs(plan, sourceStages, workspace);
            try {
                deployArtifacts(stagedOutputs);
            } catch (IOException error) {
                cleanupPaths(
                        stagedOutputs.stream().map(OutputPipeline.Staged::temporaryPath).toList(),
                        error
                );
                throw error;
            }
            return new BuildReport(
                    sourceStages.stream().map(SourceStage::report).toList(),
                    stagedOutputs.stream().map(OutputPipeline.Staged::report).toList(),
                    Duration.ofNanos(System.nanoTime() - startedAt)
            );
        }
    }

    private List<SourceStage> parseSources(
            BuildPlan plan,
            List<BuildPlan.SourceSpec> sources,
            BuildWorkspace workspace
    ) throws IOException, InterruptedException {
        SourceOpener opener = new SourceOpener(plan.sourceLoading());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SourceStage>> futures = new ArrayList<>(sources.size());
            for (BuildPlan.SourceSpec source : sources) {
                futures.add(executor.submit(() -> parseSource(plan, opener, source, workspace)));
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
                    accumulator.invalid();
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
        SourceReport report;
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
            report = new SourceReport(source.id(), 0, 1);
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
            accumulator.invalid();
            return;
        }
        if (preprocessor == null) {
            acceptOutcome(plan, parser.parseText(source, line.materialize()), accumulator);
            return;
        }
        PreprocessResult result = preprocessor.process(line, physicalLine);
        for (PreprocessorDiagnostic diagnostic : result.diagnostics()) {
            accumulator.invalid();
        }
        for (PreprocessedLine logicalLine : result.logicalLines()) {
            if (!withinLength(plan.processing(), logicalLine.line().length())) {
                accumulator.invalid();
                continue;
            }
            RuleParser.ParseOutcome outcome = source.format() == RuleFormat.EASYLIST
                    ? parser.parseAdblock(source, logicalLine.line())
                    : parser.parseText(source, logicalLine.line().materialize());
            acceptOutcome(plan, outcome, accumulator);
        }
    }

    private static void acceptOutcome(
            BuildPlan plan,
            RuleParser.ParseOutcome outcome,
            SourceAccumulator accumulator
    ) throws IOException {
        if (outcome.issue().isPresent()) {
            accumulator.invalid();
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

    private List<OutputPipeline.Staged> stageOutputs(
            BuildPlan plan,
            List<SourceStage> sourceStages,
            BuildWorkspace workspace
    ) throws IOException, InterruptedException {
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        java.util.concurrent.ConcurrentLinkedQueue<Path> temporaryPaths =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        long memoryBudget = dedupMemoryBudget(plan.outputs().size());
        Map<String, BuildPlan.SourceSpec> sourcesById = plan.sources().stream()
                .collect(Collectors.toUnmodifiableMap(BuildPlan.SourceSpec::id, source -> source));
        List<OutputPipeline> workers = new ArrayList<>(plan.outputs().size());
        for (int index = 0; index < plan.outputs().size(); index++) {
            workers.add(new OutputPipeline(
                    plan,
                    plan.outputs().get(index),
                    workspace,
                    encoder,
                    temporaryPaths,
                    workerFailure,
                    sourcesById,
                    memoryBudget
            ));
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<OutputPipeline.Staged>> futures = new ArrayList<>(workers.size());
        try {
            for (OutputPipeline worker : workers) {
                futures.add(executor.submit(worker));
            }
            streamRules(sourceStages, workers, workerFailure);
            for (OutputPipeline worker : workers) {
                worker.finishInput();
            }
            return await(futures, "输出产物生成失败");
        } catch (IOException | InterruptedException | RuntimeException error) {
            executor.shutdownNow();
            cleanupPaths(temporaryPaths, error);
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
                RuleRecord record;
                while ((record = reader.read()) != null) {
                    batch.add(new OutputPipeline.IndexedRule(sequence++, record));
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

    private void deployArtifacts(List<OutputPipeline.Staged> stagedOutputs) throws IOException {
        List<DeploymentFile> files = new ArrayList<>(stagedOutputs.size());
        for (OutputPipeline.Staged output : stagedOutputs) {
            files.add(new DeploymentFile(output.temporaryPath(), output.report().path()));
        }
        try {
            for (DeploymentFile file : files) {
                Files.move(file.temporary(), file.target(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            cleanupPaths(files.stream().map(DeploymentFile::temporary).toList(), error);
            throw new IOException("直接替换输出产物失败", error);
        }
    }

    private static long dedupMemoryBudget(int outputs) {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long divisor = Math.max(8L, outputs * 4L);
        return Math.clamp(maxHeap / divisor, MEBIBYTE, 128L * MEBIBYTE);
    }

    private static boolean withinLength(BuildPlan.ProcessingPolicy processing, int length) {
        return (processing.minRuleLength() == 0 || length >= processing.minRuleLength())
                && (processing.maxRuleLength() == 0 || length <= processing.maxRuleLength());
    }

    private static boolean excluded(Set<String> excludes, RuleRecord record) {
        return record.canonical()
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

    private static void cleanupPaths(Iterable<Path> paths, Throwable primaryError) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupError) {
                primaryError.addSuppressed(cleanupError);
            }
        }
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

        private SourceReport finish() {
            return new SourceReport(sourceId, parsed, invalid);
        }
    }

    private record DeploymentFile(Path temporary, Path target) {
    }

    public record SourceReport(
            String sourceId,
            long parsed,
            long invalid
    ) {
    }

    public record OutputReport(
            Path path,
            long approximations,
            long unsupported,
            long finalRules
    ) {
    }

    public record BuildReport(
            List<SourceReport> sources,
            List<OutputReport> outputs,
            Duration elapsed
    ) {

        public BuildReport {
            sources = List.copyOf(sources);
            outputs = List.copyOf(outputs);
            Objects.requireNonNull(elapsed, "elapsed 不能为空");
        }

        public long invalidRules() {
            return sources.stream().mapToLong(SourceReport::invalid).sum();
        }
    }
}

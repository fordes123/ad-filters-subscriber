package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.logging.LoggingConfigurator;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DnsValidationPipeline {

    private static final Logger LOGGER = LoggingConfigurator.logger(DnsValidationPipeline.class);

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final int RECORD = 1;
    private static final int END = 0;
    private static final String INVALID_REFERENCE_KEY = "!";
    private static final long MEBIBYTE = 1024L * 1024L;

    private final BuildPlan.DnsValidationPolicy policy;
    private final BuildWorkspace workspace;
    private final long sortMemoryBudget;

    DnsValidationPipeline(BuildPlan.DnsValidationPolicy policy, BuildWorkspace workspace) {
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.sortMemoryBudget = Math.clamp(
                Runtime.getRuntime().maxMemory() / 8,
                8L * MEBIBYTE,
                128L * MEBIBYTE
        );
    }

    List<SourceStage> validate(List<SourceStage> stages) throws IOException, InterruptedException {
        Objects.requireNonNull(stages, "stages 不能为空");
        if (!policy.enabled()) {
            return stages;
        }
        try {
            return validateEnabled(stages);
        } catch (IOException | RuntimeException error) {
            LOGGER.log(
                    Level.WARNING,
                    "DNS 验证失败, 待验证域名 --> 原始规则: {0}: {1} --> 保留全部原始规则并继续",
                    new Object[]{error.getClass().getSimpleName(), error.getMessage()}
            );
            return stages;
        }
    }

    private List<SourceStage> validateEnabled(List<SourceStage> stages)
            throws IOException, InterruptedException {
        Optional<Path> references = extractReferences(stages);
        if (references.isEmpty()) {
            return stages;
        }

        Path sortedReferences = DnsReferenceStore.sort(
                references.orElseThrow(),
                workspace,
                sortMemoryBudget
        );
        Path invalidReferences = workspace.createFile("dns-invalid", ".segment");
        try (DnsReferenceStore.Writer invalidWriter = DnsReferenceStore.writer(invalidReferences);
             DnsValidator validator = new DnsValidator(policy);
             ExecutorService executor = Executors.newThreadPerTaskExecutor(
                     Thread.ofVirtual().name("adfs-dns-", 0).factory())) {
            CompletionService<DomainCheck> completion = new ExecutorCompletionService<>(executor);
            validateReferences(sortedReferences, validator, completion, invalidWriter);
        } finally {
            Files.deleteIfExists(sortedReferences);
        }

        Path sortedInvalid = DnsReferenceStore.sort(
                invalidReferences,
                workspace,
                sortMemoryBudget
        );
        try {
            return filterSources(stages, sortedInvalid);
        } finally {
            Files.deleteIfExists(sortedInvalid);
        }
    }

    private Optional<Path> extractReferences(List<SourceStage> stages) throws IOException {
        DnsReferenceStore store = new DnsReferenceStore(workspace);
        try (store) {
            for (int sourceIndex = 0; sourceIndex < stages.size(); sourceIndex++) {
                SourceStage stage = stages.get(sourceIndex);
                long ordinal = 0;
                try (RuleSegment.Reader reader = RuleSegment.reader(stage.segment())) {
                    RuleRecord rule;
                    while ((rule = reader.read()) != null) {
                        if (requiresDnsValidation(rule)) {
                            store.write(new DnsReferenceStore.Reference(
                                    rule.canonical().orElseThrow().value(),
                                    sourceIndex,
                                    ordinal
                            ));
                        }
                        ordinal++;
                    }
                }
            }
        }
        return store.path();
    }

    private void validateReferences(
            Path sortedReferences,
            DnsValidator validator,
            CompletionService<DomainCheck> completion,
            DnsReferenceStore.Writer invalidWriter
    ) throws IOException, InterruptedException {
        Path domains = workspace.createFile("dns-domain", ".segment");
        writeUniqueDomains(sortedReferences, domains);
        Path statuses = workspace.createFile("dns-status", ".segment");
        queryDomains(domains, statuses, validator, completion);
        joinInvalidReferences(sortedReferences, statuses, invalidWriter);
        Files.deleteIfExists(domains);
        Files.deleteIfExists(statuses);
    }

    private static void writeUniqueDomains(Path sortedReferences, Path domains) throws IOException {
        try (DnsReferenceStore.Reader reader = DnsReferenceStore.reader(sortedReferences);
             DomainWriter writer = new DomainWriter(domains)) {
            String previous = null;
            DnsReferenceStore.Reference reference;
            while ((reference = reader.read()) != null) {
                if (!reference.domain().equals(previous)) {
                    writer.write(reference.domain());
                    previous = reference.domain();
                }
            }
        }
    }

    private void queryDomains(
            Path domains,
            Path statuses,
            DnsValidator validator,
            CompletionService<DomainCheck> completion
    ) throws IOException, InterruptedException {
        try (DomainReader reader = new DomainReader(domains);
             StatusWriter writer = new StatusWriter(statuses)) {
            TreeMap<Long, DomainCheck> completed = new TreeMap<>();
            long submitted = 0;
            long nextWrite = 0;
            int inFlight = 0;
            String nextDomain = reader.read();
            while (nextDomain != null || inFlight > 0) {
                while (nextDomain != null && submitted - nextWrite < policy.concurrency()) {
                    long sequence = submitted++;
                    String submittedDomain = nextDomain;
                    completion.submit(() -> new DomainCheck(
                            sequence,
                            submittedDomain,
                            validator.exists(submittedDomain)
                    ));
                    inFlight++;
                    nextDomain = reader.read();
                }
                DomainCheck check = await(completion.take());
                inFlight--;
                completed.put(check.sequence(), check);
                while (true) {
                    DomainCheck ordered = completed.remove(nextWrite);
                    if (ordered == null) {
                        break;
                    }
                    writer.write(ordered.domain(), ordered.exists());
                    nextWrite++;
                }
            }
            if (!completed.isEmpty() || nextWrite != submitted) {
                throw new IOException("DNS 查询结果序列不完整: submitted=" + submitted
                        + ", written=" + nextWrite);
            }
        }
    }

    private static void joinInvalidReferences(
            Path sortedReferences,
            Path statuses,
            DnsReferenceStore.Writer invalidWriter
    ) throws IOException {
        try (DnsReferenceStore.Reader references = DnsReferenceStore.reader(sortedReferences);
             StatusReader statusReader = new StatusReader(statuses)) {
            DomainStatus status = statusReader.read();
            String activeDomain = null;
            boolean activeExists = true;
            DnsReferenceStore.Reference reference;
            while ((reference = references.read()) != null) {
                if (!reference.domain().equals(activeDomain)) {
                    if (status == null || !status.domain().equals(reference.domain())) {
                        throw new IOException("DNS 状态与域名引用不一致: domain=" + reference.domain());
                    }
                    activeDomain = reference.domain();
                    activeExists = status.exists();
                    status = statusReader.read();
                }
                if (!activeExists) {
                    invalidWriter.write(new DnsReferenceStore.Reference(
                            INVALID_REFERENCE_KEY,
                            reference.sourceIndex(),
                            reference.ruleOrdinal()
                    ));
                }
            }
            if (status != null) {
                throw new IOException("DNS 状态包含没有对应规则的域名: " + status.domain());
            }
        }
    }

    private List<SourceStage> filterSources(
            List<SourceStage> stages,
            Path sortedInvalid
    ) throws IOException {
        List<SourceStage> filtered = new ArrayList<>(stages.size());
        try (DnsReferenceStore.Reader invalidReader = DnsReferenceStore.reader(sortedInvalid)) {
            DnsReferenceStore.Reference invalid = invalidReader.read();
            for (int sourceIndex = 0; sourceIndex < stages.size(); sourceIndex++) {
                SourceStage stage = stages.get(sourceIndex);
                if (invalid == null || invalid.sourceIndex() > sourceIndex) {
                    filtered.add(stage);
                    continue;
                }
                if (invalid.sourceIndex() < sourceIndex) {
                    throw new IOException("DNS 无效引用 sourceIndex 顺序错误: " + invalid.sourceIndex());
                }
                FilterResult result = filterSource(stage, sourceIndex, invalid, invalidReader);
                filtered.add(result.stage());
                invalid = result.nextInvalid();
            }
            if (invalid != null) {
                throw new IOException("DNS 无效引用超出来源范围: sourceIndex=" + invalid.sourceIndex());
            }
        }
        return List.copyOf(filtered);
    }

    private FilterResult filterSource(
            SourceStage stage,
            int sourceIndex,
            DnsReferenceStore.Reference firstInvalid,
            DnsReferenceStore.Reader invalidReader
    ) throws IOException {
        Path filteredSegment = workspace.createFile("source-validated", ".segment");
        DnsReferenceStore.Reference invalid = firstInvalid;
        long ordinal = 0;
        long removed = 0;
        try (RuleSegment.Reader reader = RuleSegment.reader(stage.segment());
             RuleSegment.Writer writer = RuleSegment.writer(filteredSegment, stage.source())) {
            RuleRecord rule;
            while ((rule = reader.read()) != null) {
                if (invalid != null
                        && invalid.sourceIndex() == sourceIndex
                        && invalid.ruleOrdinal() == ordinal) {
                    removed++;
                    invalid = invalidReader.read();
                } else {
                    if (invalid != null
                            && invalid.sourceIndex() == sourceIndex
                            && invalid.ruleOrdinal() < ordinal) {
                        throw new IOException("DNS 无效引用 ordinal 顺序错误: source="
                                + stage.source().id() + ", ordinal=" + invalid.ruleOrdinal());
                    }
                    writer.write(rule);
                }
                ordinal++;
            }
        }
        if (invalid != null && invalid.sourceIndex() == sourceIndex) {
            throw new IOException("DNS 无效引用超出来源规则范围: source=" + stage.source().id()
                    + ", ordinal=" + invalid.ruleOrdinal());
        }
        BuildEngine.SourceReport original = stage.report();
        SourceStage result = new SourceStage(stage.source(), filteredSegment, new BuildEngine.SourceReport(
                original.sourceId(),
                original.parsed() - removed,
                original.invalid() + removed
        ));
        return new FilterResult(result, invalid);
    }

    private static DomainCheck await(java.util.concurrent.Future<DomainCheck> future)
            throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException ioError) {
                throw new IOException("DNS 验证失败", ioError);
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("DNS 验证失败", cause);
        }
    }

    private static boolean requiresDnsValidation(RuleRecord record) {
        return record.canonical()
                .filter(rule -> rule.matchType() == CanonicalRule.MatchType.EXACT_DOMAIN
                        || rule.matchType() == CanonicalRule.MatchType.DOMAIN_SUFFIX)
                .filter(rule -> rule.action() == CanonicalRule.Action.BLOCK)
                .filter(rule -> rule.value().contains("."))
                .isPresent();
    }

    private record DomainCheck(long sequence, String domain, boolean exists) {
    }

    private record DomainStatus(String domain, boolean exists) {
    }

    private record FilterResult(
            SourceStage stage,
            DnsReferenceStore.Reference nextInvalid
    ) {
    }

    private static final class DomainWriter implements AutoCloseable {

        private final DataOutputStream output;
        private boolean closed;

        private DomainWriter(Path path) throws IOException {
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path), BUFFER_SIZE));
        }

        void write(String domain) throws IOException {
            output.writeByte(RECORD);
            writeString(output, domain);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            output.writeByte(END);
            output.close();
        }
    }

    private static final class DomainReader implements AutoCloseable {

        private final Path path;
        private final DataInputStream input;
        private boolean ended;

        private DomainReader(Path path) throws IOException {
            this.path = path;
            input = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(path), BUFFER_SIZE));
        }

        String read() throws IOException {
            int marker = readMarker();
            return marker == END ? null : readString(input, path);
        }

        private int readMarker() throws IOException {
            if (ended) {
                return END;
            }
            int marker;
            try {
                marker = input.readUnsignedByte();
            } catch (EOFException error) {
                throw new IOException("DNS 域名段缺少结束标记: " + path, error);
            }
            if (marker == END) {
                ended = true;
                return END;
            }
            if (marker != RECORD) {
                throw new IOException("DNS 域名段记录标记无效: path=" + path + ", marker=" + marker);
            }
            return marker;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class StatusWriter implements AutoCloseable {

        private final DataOutputStream output;
        private boolean closed;

        private StatusWriter(Path path) throws IOException {
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path), BUFFER_SIZE));
        }

        void write(String domain, boolean exists) throws IOException {
            output.writeByte(RECORD);
            writeString(output, domain);
            output.writeBoolean(exists);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            output.writeByte(END);
            output.close();
        }
    }

    private static final class StatusReader implements AutoCloseable {

        private final Path path;
        private final DataInputStream input;
        private boolean ended;

        private StatusReader(Path path) throws IOException {
            this.path = path;
            input = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(path), BUFFER_SIZE));
        }

        DomainStatus read() throws IOException {
            if (ended) {
                return null;
            }
            int marker;
            try {
                marker = input.readUnsignedByte();
            } catch (EOFException error) {
                throw new IOException("DNS 状态段缺少结束标记: " + path, error);
            }
            if (marker == END) {
                ended = true;
                return null;
            }
            if (marker != RECORD) {
                throw new IOException("DNS 状态段记录标记无效: path=" + path + ", marker=" + marker);
            }
            return new DomainStatus(readString(input, path), input.readBoolean());
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, Path path) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("DNS 段字符串长度无效: path=" + path + ", length=" + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("DNS 段字符串被截断: path=" + path + ", expected=" + length
                    + ", actual=" + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

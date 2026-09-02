package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.logging.RuleLogContext;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DnsValidationPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsValidationPipeline.class);
    private static final int PROGRESS_INTERVAL = 256;

    private final BuildPlan.DnsValidationPolicy policy;
    private final BuildWorkspace workspace;
    private final LongConsumer progress;

    DnsValidationPipeline(
            BuildPlan.DnsValidationPolicy policy,
            BuildWorkspace workspace,
            LongConsumer progress
    ) {
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.progress = Objects.requireNonNull(progress, "progress 不能为空");
    }

    List<SourceStage> validate(List<SourceStage> stages) throws IOException, InterruptedException {
        Objects.requireNonNull(stages, "stages 不能为空");
        if (!policy.enabled()) {
            return stages;
        }
        return validateEnabled(stages);
    }

    private List<SourceStage> validateEnabled(List<SourceStage> stages)
            throws IOException, InterruptedException {
        AtomicLong completed = new AtomicLong();
        List<SourceStage> result;
        try (DnsValidator validator = new DnsValidator(policy)) {
            result = filterSources(stages, validator, completed);
        }
        progress.accept(completed.get());
        return result;
    }

    private List<SourceStage> filterSources(
            List<SourceStage> stages,
            DnsValidator validator,
            AtomicLong completed
    ) throws IOException, InterruptedException {
        List<SourceStage> filtered = new ArrayList<>(stages.size());
        for (SourceStage stage : stages) {
            filtered.add(filterSource(stage, validator, completed));
        }
        return List.copyOf(filtered);
    }

    private SourceStage filterSource(
            SourceStage stage,
            DnsValidator validator,
            AtomicLong completed
    ) throws IOException, InterruptedException {
        Path filteredSegment = workspace.createFile("source-validated", ".segment");
        Deque<PendingRule> pending = new ArrayDeque<>(policy.concurrency());
        long removed = 0;
        try (RuleSegment.Reader reader = RuleSegment.reader(stage.segment());
             RuleSegment.Writer writer = RuleSegment.writer(filteredSegment, stage.source())) {
            RuleRecord rule;
            while ((rule = reader.read()) != null) {
                Optional<String> domain = dnsDomain(rule);
                pending.addLast(domain.isPresent()
                        ? new PendingRule(rule, validator.resolveAsync(domain.orElseThrow()))
                        : new PendingRule(rule));
                if (pending.size() == policy.concurrency()) {
                    removed += writeFirst(pending, writer, completed, stage);
                }
            }
            while (!pending.isEmpty()) {
                removed += writeFirst(pending, writer, completed, stage);
            }
        }
        BuildReport.Source original = stage.report();
        return new SourceStage(stage.source(), filteredSegment, new BuildReport.Source(
                original.sourceId(),
                original.parsed() - removed,
                original.invalid() + removed
        ));
    }

    private long writeFirst(
            Deque<PendingRule> pending,
            RuleSegment.Writer writer,
            AtomicLong completed,
            SourceStage stage
    ) throws IOException, InterruptedException {
        PendingRule current = pending.removeFirst();
        if (current.future() == null) {
            writer.write(current.rule());
            return 0;
        }
        DnsValidator.Status status = await(current.future());
        long count = completed.incrementAndGet();
        if (count % PROGRESS_INTERVAL == 0) {
            progress.accept(count);
        }
        if (status == DnsValidator.Status.INVALID) {
            LOGGER.warn(
                    "规则解析失败, {}: {} --> {}: {}",
                    RuleLogContext.source(stage.source()),
                    current.rule().raw(),
                    "DNS_NO_ADDRESS",
                    "DNS 验证未找到 A/AAAA 记录"
            );
            return 1;
        }
        writer.write(current.rule());
        return 0;
    }

    private static DnsValidator.Status await(Future<DnsValidator.Status> future)
            throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException ioError) {
                throw ioError;
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("DNS 验证任务失败", cause);
        }
    }

    private static Optional<String> dnsDomain(RuleRecord record) {
        return record.body().canonicalRule()
                .filter(rule -> rule.matchType() == CanonicalRule.MatchType.EXACT_DOMAIN
                        || rule.matchType() == CanonicalRule.MatchType.DOMAIN_SUFFIX)
                .filter(rule -> rule.action() == CanonicalRule.Action.BLOCK)
                .filter(rule -> rule.value().contains("."))
                .map(CanonicalRule::value);
    }

    private record PendingRule(RuleRecord rule, Future<DnsValidator.Status> future) {

        private PendingRule(RuleRecord rule) {
            this(rule, null);
        }
    }
}

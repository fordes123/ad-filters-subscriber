package dev.fordes.adfs.application;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.MDC;

import jakarta.inject.Singleton;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import dev.fordes.adfs.config.EffectiveConfig;
import dev.fordes.adfs.config.InputSpec;
import dev.fordes.adfs.config.OutputSpec;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.error.InputException;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.format.FormatRegistry;
import dev.fordes.adfs.format.OutputSet;
import dev.fordes.adfs.format.OutputTarget;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.format.WriteResult;
import dev.fordes.adfs.format.adblock.DisableIndex;
import dev.fordes.adfs.publish.OutputPublisher;
import dev.fordes.adfs.publish.OutputHeader;
import dev.fordes.adfs.publish.PublishManifest;
import dev.fordes.adfs.publish.StagingWorkspace.Workspace;
import dev.fordes.adfs.publish.StagingWorkspace;
import dev.fordes.adfs.report.ProcessingMetrics;
import dev.fordes.adfs.rule.dedup.CanonicalStore;
import dev.fordes.adfs.rule.dedup.RuleDeduplicator;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.spool.RuleSpool;
import dev.fordes.adfs.source.SourceReader;
import dev.fordes.adfs.source.SourceSession;
import dev.fordes.adfs.validation.dns.DnsValidator;

@Singleton
@RequiredArgsConstructor
@Slf4j
public final class ProcessingPipeline {

    private static final String SPOOL_NAME = "rules.bin";
    private static final String CANONICAL_NAME = "canonical.bin";
    private static final String DISABLE_NAME = "disabled.bin";
    private final FormatRegistry formats;
    private final List<SourceReader> sourceReaders;
    private final StagingWorkspace stagingWorkspace;
    private final OutputPublisher publisher;
    private final DnsValidator dnsValidator;

    public ProcessingResult process(EffectiveConfig config) {
        OffsetDateTime generatedAt = OffsetDateTime.now();
        formats.validateSupported(config);
        log.info("开始处理，输入源 {} 个，输出目标 {} 个，DNS 检测{}",
                config.inputs().size(), config.outputs().size(), config.dns().enabled() ? "已启用" : "未启用");
        boolean logContext = log.isDebugEnabled();
        Map<String, String> previousContext = logContext ? MDC.getCopyOfContextMap() : null;
        ProcessingMetrics metrics = new ProcessingMetrics(config.inputs().size());
        config.outputs().forEach(metrics::register);
        try (Workspace workspace = stagingWorkspace.open(config.outputDir())) {
            Path spoolPath = workspace.runDir().resolve(SPOOL_NAME);
            try (RuleSpool spool = new RuleSpool(spoolPath, config.rules().maxLength())) {
                parseInputs(config, spool, metrics);
                spool.finishWriting();
                log.info("输入解析完成，开始去重、{}规则转换", config.dns().enabled() ? "DNS 检测及" : "");
                replay(config, workspace, spool, metrics, generatedAt);
            }
            log.info("规则处理完成，开始校验并发布产物");
            PublishManifest manifest = PublishManifest.create(workspace, config.outputs());
            publisher.publish(workspace, manifest);
            log.info("产物发布完成，输出目录为「{}」", config.outputDir());
        } finally {
            if (logContext) {
                if (previousContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContext);
                }
            }
        }
        return metrics.snapshot();
    }

    private void parseInputs(EffectiveConfig config, RuleSpool spool, ProcessingMetrics metrics) {
        for (InputSpec input : config.inputs()) {
            long started = System.nanoTime();
            log.info("开始解析输入源「{}」", input.name());
            if (log.isDebugEnabled()) {
                String format = input.type().value();
                if (input.dialect() != RuleDialect.NONE) {
                    format += "/" + input.dialect().value();
                }
                MDC.put(RuleSpool.INPUT, input.name() + " (" + format + ")");
                MDC.remove(RuleSpool.INPUT_RULE);
            }
            SourceReader sourceReader = sourceReaders.stream()
                    .filter(reader -> reader.supports(input))
                    .findFirst()
                    .orElseThrow(() -> new InputException("没有可用的来源读取器: input=" + input.name()));
            RuleParser parser = formats.createParser(input, config);
            try (SourceSession session = sourceReader.open(input, config)) {
                parser.parse(session, entry -> {
                    metrics.parsed(entry);
                    spool.accept(entry);
                });
            }
            log.info("输入源「{}」解析完成，耗时 {} ms", input.name(), (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void replay(
            EffectiveConfig config,
            Workspace workspace,
            RuleSpool spool,
            ProcessingMetrics metrics,
            OffsetDateTime generatedAt) {
        try (CanonicalStore store = new CanonicalStore(workspace.runDir().resolve(CANONICAL_NAME));
                DisableIndex disabled = createDisableIndex(workspace, spool);
                OutputSet outputs = createOutputs(config, workspace)) {
            RuleDeduplicator deduplicator = new RuleDeduplicator(store);
            if (config.dns().enabled()) {
                dnsValidator.validate(spool, config.dns(), deduplicator,
                        entry -> enabled(entry, disabled),
                        entry -> distributeUnique(entry, outputs, metrics), metrics);
            } else {
                spool.replay(entry -> {
                    if (enabled(entry, disabled)) {
                        distribute(entry, deduplicator, outputs, metrics);
                    }
                });
            }
            metrics.finishHashTable(deduplicator.size(), deduplicator.capacity(), deduplicator.collisions());
            outputs.targets().forEach(OutputTarget::finish);
            for (OutputTarget output : outputs.targets()) {
                output.close();
                OutputHeader.prepend(workspace, output.spec(), generatedAt, output.ruleCount());
            }
        }
    }

    private static DisableIndex createDisableIndex(Workspace workspace, RuleSpool spool) {
        DisableIndex disabled = new DisableIndex(workspace.runDir().resolve(DISABLE_NAME));
        try {
            spool.replay(entry -> {
                if (disabled.isControl(entry)) {
                    disabled.disable(entry);
                }
            });
            return disabled;
        } catch (RuntimeException exception) {
            try {
                disabled.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static boolean enabled(RuleEntry entry, DisableIndex disabled) {
        return !disabled.isControl(entry) && !disabled.isDisabled(entry);
    }

    private OutputSet createOutputs(EffectiveConfig config, Workspace workspace) {
        OutputSet outputs = new OutputSet();
        int index = 0;
        try {
            for (OutputSpec spec : config.outputs()) {
                outputs.add(formats.createWriter(spec, config, workspace.nextDir(), workspace.runDir(), index));
                index++;
            }
            return outputs;
        } catch (RuntimeException exception) {
            try {
                outputs.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static void distribute(
            RuleEntry entry,
            RuleDeduplicator deduplicator,
            OutputSet outputs,
            ProcessingMetrics metrics) {
        if (!deduplicator.add(entry)) {
            metrics.duplicate();
            return;
        }
        metrics.unique();
        distributeUnique(entry, outputs, metrics);
    }

    private static void distributeUnique(RuleEntry entry, OutputSet outputs, ProcessingMetrics metrics) {
        boolean opaqueHandled = false;
        for (OutputTarget output : outputs.targets()) {
            WriteResult result = output.write(entry);
            metrics.wrote(output.spec(), result);
            if (result == WriteResult.PASSTHROUGH || result == WriteResult.WHITELIST_REMOVED
                    || result == WriteResult.DUPLICATE) {
                opaqueHandled = true;
            }
        }
        if (entry instanceof OpaqueRule opaque && !opaqueHandled) {
            throw new RuleProcessingException("不透明规则没有兼容输出: type="
                    + opaque.type().value() + ", dialect=" + opaque.dialect().value());
        }
    }
}

package org.fordes.adfs.cli;

import org.fordes.adfs.ast.RuleAst;
import org.fordes.adfs.preprocess.AdblockPreprocessor;
import org.fordes.adfs.preprocess.PreprocessResult;
import org.fordes.adfs.preprocess.PreprocessedLine;
import org.fordes.adfs.preprocess.PreprocessorDiagnostic;
import org.fordes.adfs.source.StreamingLineReader;
import org.fordes.adfs.syntax.DecodeResult;
import org.fordes.adfs.syntax.adblock.AdblockDecoder;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

@Command(
        name = "check",
        mixinStandardHelpOptions = true,
        description = "流式解析并检查一个或多个本地规则文件"
)
public final class CheckCommand implements Callable<Integer> {

    private static final int READ_BUFFER_SIZE = 16 * 1024;

    @Parameters(arity = "1..*", paramLabel = "<file>", description = "本地规则文件")
    private List<Path> sources;

    @Option(names = "--dialect", defaultValue = "ABP", description = "方言：${COMPLETION-CANDIDATES}")
    private DialectProfile dialect;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        List<SourceStats> results = parseSources();
        SourceStats total = SourceStats.empty();
        for (SourceStats result : results) {
            print(result);
            total = total.add(result);
        }
        spec.commandLine().getOut().printf(
                "total raw=%d decoded=%d invalid=%d%n",
                total.raw(), total.decoded(), total.invalid());
        return total.invalid() == 0 ? 0 : 2;
    }

    private List<SourceStats> parseSources() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SourceStats>> futures = new ArrayList<>(sources.size());
            for (Path source : sources) {
                futures.add(executor.submit(() -> parseSource(source)));
            }

            List<SourceStats> results = new ArrayList<>(sources.size());
            for (Future<SourceStats> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new ExecutionException(spec.commandLine(), "规则检查被中断", error);
                } catch (java.util.concurrent.ExecutionException error) {
                    Throwable cause = error.getCause();
                    throw new ExecutionException(
                            spec.commandLine(),
                            "读取或解析规则源失败",
                            cause
                    );
                }
            }
            return List.copyOf(results);
        }
    }

    private SourceStats parseSource(Path source) throws IOException {
        LongAdder raw = new LongAdder();
        LongAdder decoded = new LongAdder();
        LongAdder invalid = new LongAdder();
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        AdblockDecoder decoder = new AdblockDecoder();
        AdblockPreprocessor preprocessor = new AdblockPreprocessor(dialect);
        StreamingLineReader reader = new StreamingLineReader();

        try (InputStream input = Files.newInputStream(source)) {
            reader.read(input, READ_BUFFER_SIZE, line -> {
                raw.increment();
                long physicalLine = raw.longValue();
                PreprocessResult preprocessed = preprocessor.process(line, physicalLine);
                for (PreprocessorDiagnostic diagnostic : preprocessed.diagnostics()) {
                    invalid.increment();
                    diagnostics.add(new SourceDiagnostic(
                            diagnostic.physicalLine(),
                            diagnostic.code(),
                            diagnostic.message()
                    ));
                }

                for (PreprocessedLine logicalLine : preprocessed.logicalLines()) {
                    DecodeResult<RuleAst> result = decoder.decode(
                            logicalLine.line(),
                            dialect
                    );
                    if (result instanceof DecodeResult.Invalid<RuleAst> failure) {
                        invalid.increment();
                        diagnostics.add(new SourceDiagnostic(
                                logicalLine.physicalStartLine(),
                                failure.diagnostic().code(),
                                failure.diagnostic().message()
                        ));
                    } else {
                        decoded.increment();
                    }
                }
            });
        }

        for (PreprocessorDiagnostic diagnostic : preprocessor.finish()) {
            invalid.increment();
            diagnostics.add(new SourceDiagnostic(
                    diagnostic.physicalLine(),
                    diagnostic.code(),
                    diagnostic.message()
            ));
        }
        return new SourceStats(
                source,
                raw.longValue(),
                decoded.longValue(),
                invalid.longValue(),
                diagnostics
        );
    }

    private void print(SourceStats stats) {
        spec.commandLine().getOut().printf(
                "%s raw=%d decoded=%d invalid=%d%n",
                stats.source(), stats.raw(), stats.decoded(), stats.invalid());
        for (SourceDiagnostic diagnostic : stats.diagnostics()) {
            spec.commandLine().getOut().printf(
                    "%s:%d [%s] %s%n",
                    stats.source(), diagnostic.physicalLine(), diagnostic.code(), diagnostic.message());
        }
    }

    private record SourceDiagnostic(long physicalLine, String code, String message) {
    }

    private record SourceStats(
            Path source,
            long raw,
            long decoded,
            long invalid,
            List<SourceDiagnostic> diagnostics
    ) {

        private SourceStats {
            diagnostics = List.copyOf(diagnostics);
        }

        private static SourceStats empty() {
            return new SourceStats(null, 0, 0, 0, List.of());
        }

        private SourceStats add(SourceStats other) {
            return new SourceStats(
                    null,
                    raw + other.raw,
                    decoded + other.decoded,
                    invalid + other.invalid,
                    List.of()
            );
        }
    }
}

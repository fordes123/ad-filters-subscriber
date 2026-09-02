package org.fordes.adfs.cli;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.config.ConfigLoader;
import org.fordes.adfs.engine.BuildEngine;
import org.fordes.adfs.engine.BuildReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command(
        name = "build",
        mixinStandardHelpOptions = true,
        description = "从配置文件读取规则源并生成全部输出产物"
)
public final class BuildCommand implements Callable<Integer> {

    @Option(
            names = {"-c", "--config"},
            defaultValue = "config/application.yaml",
            paramLabel = "<file>",
            description = "构建配置文件"
    )
    private Path configPath;

    @Option(
            names = "--output-directory",
            paramLabel = "<directory>",
            description = "覆盖 application.output.path"
    )
    private Optional<Path> outputDirectory = Optional.empty();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException, InterruptedException {
        try {
            BuildPlan plan = ConfigLoader.load(configPath);
            if (outputDirectory.isPresent()) {
                plan = plan.withOutputDirectory(outputDirectory.orElseThrow());
            }
            Logger logger = LoggerFactory.getLogger(BuildCommand.class);
            BuildReport report;
            try (TerminalBuildProgress progress = new TerminalBuildProgress(
                    spec.commandLine().getErr(),
                    spec.commandLine().getColorScheme().ansi()
            )) {
                report = new BuildEngine().build(plan, progress);
            }
            logger.info(
                    "构建完成, 输入源 {} 个 --> 输出文件 {} 个: 无效规则 {} 条，耗时 {} ms",
                    report.sources().size(),
                    report.outputs().size(),
                    report.invalidRules(),
                    report.elapsed().toMillis()
            );
            new BuildResultPrinter(
                    spec.commandLine().getOut(),
                    spec.commandLine().getColorScheme()
            ).print(report);
            return 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
    }

}

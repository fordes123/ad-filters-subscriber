package org.fordes.adfs.cli;

import org.fordes.adfs.AdFSApplication;
import org.fordes.adfs.console.ConsoleReporter;
import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.config.ConfigLoader;
import org.fordes.adfs.engine.BuildEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

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

    @ParentCommand
    private AdFSApplication application;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException, InterruptedException {
        ConsoleReporter reporter = application.reporter();
        try {
            BuildPlan plan = new ConfigLoader().load(configPath, outputDirectory);
            reporter.status(
                    "开始处理 %,d 个规则源，生成 %,d 个文件".formatted(
                            plan.sources().size(),
                            plan.outputs().size()
                    )
            );
            BuildEngine.BuildReport report = new BuildEngine(reporter).build(plan);
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

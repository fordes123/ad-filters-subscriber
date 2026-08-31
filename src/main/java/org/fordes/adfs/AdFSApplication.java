package org.fordes.adfs;

import org.fordes.adfs.cli.BuildCommand;
import org.fordes.adfs.cli.CheckCommand;
import org.fordes.adfs.cli.InspectCommand;
import org.fordes.adfs.console.ConsoleReporter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Command(
        name = "adfs",
        mixinStandardHelpOptions = true,
        versionProvider = AdFSApplication.VersionProvider.class,
        description = "Aggregate and convert ad-filter subscriptions",
        subcommands = {BuildCommand.class, InspectCommand.class, CheckCommand.class}
)
public final class AdFSApplication implements Runnable {

    private static final String BANNER = """
                _    ____  _____ ____
               / \\  |  _ \\|  ___/ ___|
              / _ \\ | | | | |_  \\___ \\
             / ___ \\| |_| |  _|  ___) |
            /_/   \\_\\____/|_|   |____/

            AD Filter Subscriber

            """;

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        CommandLine commandLine = commandLine();
        commandLine.getOut().print(BANNER);
        commandLine.getOut().flush();
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    public static CommandLine commandLine() {
        return new CommandLine(new AdFSApplication())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(AdFSApplication::handleExecutionException)
                .setExpandAtFiles(false);
    }

    private static int handleExecutionException(
            Exception error,
            CommandLine commandLine,
            CommandLine.ParseResult parseResult
    ) {
        AdFSApplication application = (AdFSApplication) commandLine
                .getCommandSpec()
                .root()
                .userObject();
        application.reporter().failure("命令执行失败", error);
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public ConsoleReporter reporter() {
        return new ConsoleReporter(
                spec.commandLine().getErr(),
                spec.commandLine().getColorScheme().ansi()
        );
    }

    public static final class VersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream input = AdFSApplication.class.getResourceAsStream("/adfs-version.properties")) {
                if (input == null) {
                    throw new IOException("缺少版本资源: adfs-version.properties");
                }
                properties.load(input);
            }
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IOException("版本资源缺少 version");
            }
            return new String[]{"AD Filter Subscriber " + version};
        }
    }
}

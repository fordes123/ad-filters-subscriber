package org.fordes.adfs.cli;

import org.fordes.adfs.AdFSApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CliSmokeTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void printsApplicationVersion() {
        StringWriter output = new StringWriter();

        int exitCode = commandLine(output).execute("--version");

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("AD Filter Subscriber "));
    }

    @Test
    void inspectsNetworkRule() {
        StringWriter output = new StringWriter();
        CommandLine commandLine = commandLine(output);

        int exitCode = commandLine.execute(
                "inspect",
                "--dialect=UBO",
                "@@||example.com^$script,important"
        );

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("status=decoded"));
        assertTrue(output.toString().contains("pattern=example.com^"));
        assertTrue(output.toString().contains("modifier=script"));
    }

    @Test
    void checksLocalFileWithStreamingReader() throws Exception {
        Path source = tempDirectory.resolve("rules.txt");
        Files.writeString(
                source,
                "! title\n||example.com^\nexample.com##.advert\n",
                StandardCharsets.UTF_8
        );
        StringWriter output = new StringWriter();
        CommandLine commandLine = commandLine(output);

        int exitCode = commandLine.execute("check", source.toString());

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("raw=3 decoded=3 invalid=0"));
        assertTrue(output.toString().contains("total raw=3 decoded=3 invalid=0"));
    }

    @Test
    void checksUboContinuationAsOneLogicalRule() throws Exception {
        Path source = tempDirectory.resolve("ubo-rules.txt");
        Files.writeString(
                source,
                "||example.com^$domain=a.com| \\\n    b.com\n",
                StandardCharsets.UTF_8
        );
        StringWriter output = new StringWriter();
        CommandLine commandLine = commandLine(output);

        int exitCode = commandLine.execute("check", "--dialect=UBO", source.toString());

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("raw=2 decoded=1 invalid=0"));
    }

    @Test
    void returnsNonZeroForInvalidRule() throws Exception {
        Path source = tempDirectory.resolve("invalid-rules.txt");
        Files.writeString(source, "||$script\n", StandardCharsets.UTF_8);
        StringWriter output = new StringWriter();
        CommandLine commandLine = commandLine(output);

        int exitCode = commandLine.execute("check", source.toString());

        assertEquals(2, exitCode);
        assertTrue(output.toString().contains("[EMPTY_NETWORK_PATTERN]"));
    }

    private static CommandLine commandLine(StringWriter output) {
        CommandLine commandLine = AdFSApplication.commandLine();
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(output, true));
        return commandLine;
    }
}

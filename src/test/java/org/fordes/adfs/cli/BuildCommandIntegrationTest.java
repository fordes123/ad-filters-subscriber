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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildCommandIntegrationTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void buildsAllFormatsFromConfiguration() throws Exception {
        Path adblockSource = tempDirectory.resolve("adblock.txt");
        Path hostsSource = tempDirectory.resolve("hosts.txt");
        Path outputDirectory = tempDirectory.resolve("dist");
        Path config = tempDirectory.resolve("build.yaml");

        Files.writeString(
                adblockSource,
                "! title\n||ads.example.com^\n@@||allow.example.com^\n/track.*/\nexample.com##.advert\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                hostsSource,
                "0.0.0.0 hosts.test-domain.com\n1.2.3.4 rewrite.test-domain.com\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                config,
                """
                application:
                  input:
                    - name: adblock-source
                      path: '%s'
                      type: easylist
                      dialect: ubo
                    - name: hosts-source
                      path: '%s'
                      type: hosts
                  output:
                    path: '%s'
                    file_header: |
                      Generated ${name} ${type}
                      Description ${desc}
                      Date ${date}
                      Total ${total}
                    files:
                      - name: adblock.txt
                        type: easylist
                        dialect: ubo
                        desc: Browser rules
                      - name: hosts.txt
                        type: hosts
                      - name: dnsmasq.conf
                        type: dnsmasq
                      - name: smartdns.conf
                        type: smartdns
                      - name: adguard-dns.txt
                        type: dns
                        dialect: adguard
                      - name: clash.yaml
                        type: clash
                      - name: sing-box.json
                        type: sing-box
                  processing:
                    allow-narrowing: true
                    allow-broadening: true
                """.formatted(
                        yamlPath(adblockSource),
                        yamlPath(hostsSource),
                        yamlPath(outputDirectory)
                ),
                StandardCharsets.UTF_8
        );

        StringWriter output = new StringWriter();
        CommandLine commandLine = commandLine(output);
        int exitCode = commandLine.execute("build", "--config", config.toString());

        assertEquals(0, exitCode, output.toString());
        String adblock = Files.readString(outputDirectory.resolve("adblock.txt"));
        assertTrue(adblock.contains("! Generated adblock.txt easylist"));
        assertTrue(adblock.contains("! Description Browser rules"));
        assertTrue(adblock.matches("(?s).*![ ]Date \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"));
        assertTrue(adblock.contains("! Total 5"));
        assertTrue(adblock.contains("||ads.example.com^"));
        assertTrue(adblock.contains("example.com##.advert"));
        assertTrue(Files.readString(outputDirectory.resolve("hosts.txt"))
                .contains("0.0.0.0\tads.example.com"));
        assertTrue(Files.readString(outputDirectory.resolve("hosts.txt"))
                .contains("1.2.3.4\trewrite.test-domain.com"));
        assertTrue(Files.readString(outputDirectory.resolve("dnsmasq.conf"))
                .contains("address=/ads.example.com/"));
        assertTrue(Files.readString(outputDirectory.resolve("smartdns.conf"))
                .contains("address /ads.example.com/#"));
        assertTrue(Files.readString(outputDirectory.resolve("adguard-dns.txt"))
                .contains("||ads.example.com^"));
        String clash = Files.readString(outputDirectory.resolve("clash.yaml"));
        assertTrue(clash.contains("payload:"));
        assertTrue(clash.contains("  - 'DOMAIN-SUFFIX,ads.example.com'"));
        String singBox = Files.readString(outputDirectory.resolve("sing-box.json"));
        assertTrue(singBox.contains("\"version\""));
        assertTrue(singBox.contains("2"));
        assertTrue(singBox.contains("\"domain_suffix\""));
        assertTrue(singBox.contains("ads.example.com"));
        assertTrue(output.toString().contains("2 个源"));
        assertTrue(output.toString().contains("7 个文件"));
        assertTrue(output.toString().contains("hosts.txt"));
        assertTrue(output.toString().contains(outputDirectory.toString()));
    }

    @Test
    void buildsWithSourceProjectionAndHeader() throws Exception {
        Path source = tempDirectory.resolve("hosts.txt");
        Path outputDirectory = tempDirectory.resolve("dist");
        Path config = tempDirectory.resolve("build.yaml");
        Files.writeString(source, "0.0.0.0 fixture.test-domain.com\n", StandardCharsets.UTF_8);
        Files.writeString(
                config,
                """
                application:
                  input:
                    - name: hosts-source
                      path: '%s'
                      type: hosts
                  output:
                    path: '%s'
                    file_header: 'Generated ${total}'
                    files:
                      - name: hosts.txt
                        type: hosts
                        file_header: ''
                        rule:
                          - hosts-source
                """.formatted(yamlPath(source), yamlPath(outputDirectory)),
                StandardCharsets.UTF_8
        );

        StringWriter output = new StringWriter();
        int exitCode = commandLine(output).execute("build", "-c", config.toString());

        assertEquals(0, exitCode, output.toString());
        String hosts = Files.readString(outputDirectory.resolve("hosts.txt"));
        assertTrue(hosts.contains("# Generated 1"));
        assertTrue(hosts.contains("0.0.0.0\tfixture.test-domain.com"));
    }

    @Test
    void commandLineOutputDirectoryOverridesConfiguration() throws Exception {
        Path source = tempDirectory.resolve("override-source.txt");
        Path configuredDirectory = tempDirectory.resolve("configured-output");
        Path overriddenDirectory = tempDirectory.resolve("overridden-output");
        Path config = tempDirectory.resolve("override.yaml");
        Files.writeString(source, "||override.test-domain.com^\n", StandardCharsets.UTF_8);
        Files.writeString(config, """
                application:
                  input:
                    - name: source
                      path: '%s'
                  output:
                    path: '%s'
                    files:
                      - name: rules.txt
                        type: easylist
                """.formatted(yamlPath(source), yamlPath(configuredDirectory)), StandardCharsets.UTF_8);

        StringWriter output = new StringWriter();
        int exitCode = commandLine(output).execute(
                "build",
                "--config",
                config.toString(),
                "--output-directory",
                overriddenDirectory.toString()
        );

        assertEquals(0, exitCode, output.toString());
        assertTrue(Files.readString(overriddenDirectory.resolve("rules.txt"))
                .contains("||override.test-domain.com^"));
        assertFalse(Files.exists(configuredDirectory.resolve("rules.txt")));
    }

    @Test
    void appliesExclusionsLengthLimitsAndDeduplication() throws Exception {
        Path source = tempDirectory.resolve("rules.txt");
        Path outputDirectory = tempDirectory.resolve("dist-filtered");
        Path config = tempDirectory.resolve("filtered.yaml");
        Files.writeString(
                source,
                """
                ||keep.test-domain.com^
                ||keep.test-domain.com^
                ||excluded.test-domain.com^
                x
                """,
                StandardCharsets.UTF_8
        );
        Files.writeString(config, """
                application:
                  input:
                    - name: source
                      path: '%s'
                      type: easylist
                  output:
                    path: '%s'
                    files:
                      - name: rules.txt
                        type: easylist
                  processing:
                    min-rule-length: 2
                    excluded-domains: [excluded.test-domain.com]
                """.formatted(yamlPath(source), yamlPath(outputDirectory)), StandardCharsets.UTF_8);
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("rules.txt"), "old-content\n", StandardCharsets.UTF_8);

        StringWriter output = new StringWriter();
        int exitCode = commandLine(output).execute("build", "--config", config.toString());

        assertEquals(0, exitCode, output.toString());
        String rules = Files.readString(outputDirectory.resolve("rules.txt"));
        assertEquals(1, rules.lines().filter(line -> line.equals("||keep.test-domain.com^")).count());
        assertFalse(rules.contains("excluded.test-domain.com"));
        assertFalse(rules.contains("old-content"));
        assertTrue(output.toString().contains("无效规则 1"));
    }

    private static CommandLine commandLine(StringWriter output) {
        CommandLine commandLine = AdFSApplication.commandLine();
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(output, true));
        return commandLine;
    }

    private static String yamlPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("'", "''");
    }
}

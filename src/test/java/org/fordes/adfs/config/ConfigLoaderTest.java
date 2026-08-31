package org.fordes.adfs.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigLoaderTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void loadsCompleteConfigurationAndNormalizesValues() throws Exception {
        Path config = tempDirectory.resolve("complete.yaml");
        Files.writeString(config, """
                application:
                  input:
                    - name: primary
                      path: rules.txt
                      type: sing-box
                      priority: 9
                  output:
                    path: dist
                    file_header: 'Global ${total}'
                    files:
                      - name: rules.yaml
                        type: clash
                        dialect: domain
                        desc: Domain rules
                        rule: [primary]
                  source-loading:
                    local-charset: UTF-16LE
                    http-charset: UTF-8
                    buffer-size: 8KB
                    connect-timeout: 250ms
                    request-timeout: PT2S
                  processing:
                    min-rule-length: 2
                    max-rule-length: 200
                    excluded-domains: [EXAMPLE.COM]
                    allow-narrowing: false
                    allow-broadening: true
                    dns-validation:
                      enabled: false
                      timeout: 2s
                      concurrency: 16
                  logging:
                    include-successful-conversions: true
                """, StandardCharsets.UTF_8);

        BuildPlan plan = new ConfigLoader().load(config);

        assertEquals(1, plan.sources().size());
        assertEquals(9, plan.sources().getFirst().priority());
        assertEquals("Global ${total}", plan.outputs().getFirst().header());
        assertEquals(Set.of("primary"), plan.outputs().getFirst().sources());
        assertEquals(Set.of("example.com"), plan.processing().excludedDomains());
        assertEquals(8192, plan.sourceLoading().localBufferSize());
        assertEquals(Duration.ofMillis(250), plan.sourceLoading().connectTimeout());
        assertEquals(Duration.ofSeconds(2), plan.sourceLoading().requestTimeout());
        assertFalse(plan.processing().allowNarrowing());
        assertTrue(plan.processing().allowBroadening());
        assertTrue(plan.logging().includeSuccessfulConversions());
    }

    @Test
    void rejectsUnsupportedFormatName() throws Exception {
        IllegalArgumentException error = loadInvalidConfig("""
                application:
                  input:
                    - name: source
                      path: rules.txt
                      type: adblock
                  output:
                    files:
                      - name: rules.txt
                        type: easylist
                """);

        assertTrue(error.getMessage().contains("未知规则格式"));
    }

    @Test
    void rejectsNonBooleanNarrowingSetting() throws Exception {
        IllegalArgumentException error = loadInvalidConfig("""
                application:
                  input:
                    - name: source
                      path: rules.txt
                      type: easylist
                  output:
                    files:
                      - name: rules.txt
                        type: easylist
                  processing:
                    allow-narrowing: enabled
                """);

        assertTrue(error.getMessage().contains("必须是 boolean"));
    }

    @Test
    void rejectsUnknownNestedField() throws Exception {
        IllegalArgumentException error = loadInvalidConfig("""
                application:
                  input:
                    - name: source
                      path: rules.txt
                  output:
                    files:
                      - name: rules.txt
                        type: easylist
                  source-loading:
                    retries: 3
                """);

        assertTrue(error.getMessage().contains("未知字段: retries"));
    }

    @Test
    void disablesSuccessfulConversionLogsByDefault() throws Exception {
        Path config = tempDirectory.resolve("defaults.yaml");
        Files.writeString(config, baseConfig(""), StandardCharsets.UTF_8);

        BuildPlan plan = new ConfigLoader().load(config);

        assertFalse(plan.logging().includeSuccessfulConversions());
    }

    @Test
    void rejectsLegacyConfigSection() throws Exception {
        IllegalArgumentException error = loadInvalidConfig("""
                application:
                  input:
                    - name: source
                      path: rules.txt
                  output:
                    files:
                      - name: rules.txt
                        type: easylist
                  config:
                    conversion:
                      allow-narrowing: true
                """);

        assertTrue(error.getMessage().contains("未知字段: config"));
    }

    @Test
    void rejectsInvalidDurationAndDataSize() throws Exception {
        IllegalArgumentException duration = loadInvalidConfig(baseConfig("""
                source-loading:
                  connect-timeout: soon
                """));
        IllegalArgumentException size = loadInvalidConfig(baseConfig("""
                source-loading:
                  buffer-size: huge
                """));

        assertTrue(duration.getMessage().contains("无效 duration"));
        assertTrue(size.getMessage().contains("无效 data size"));
    }

    @Test
    void requiresDnsServerAndRejectsPortWithoutServer() throws Exception {
        IllegalArgumentException missingServer = loadInvalidConfig(baseConfig("""
                processing:
                  dns-validation:
                    enabled: true
                """));
        IllegalArgumentException orphanPort = loadInvalidConfig(baseConfig("""
                processing:
                  dns-validation:
                    port: 5353
                """));

        assertTrue(missingServer.getMessage().contains("必须配置 server"));
        assertTrue(orphanPort.getMessage().contains("需要同时配置 server"));
    }

    private static String baseConfig(String configSection) {
        return """
                application:
                  input:
                    - name: source
                      path: rules.txt
                  output:
                    files:
                      - name: rules.txt
                        type: easylist
                """ + configSection.indent(2);
    }

    private IllegalArgumentException loadInvalidConfig(String content) throws Exception {
        Path config = tempDirectory.resolve("invalid.yaml");
        Files.writeString(config, content, StandardCharsets.UTF_8);
        return assertThrows(IllegalArgumentException.class, () -> new ConfigLoader().load(config));
    }
}

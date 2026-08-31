package org.fordes.adfs.config;

import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildPlanTest {

    @Test
    void rejectsDuplicateSourcesOutputsAndUnknownProjection() {
        BuildPlan.SourceSpec source = source("source");
        BuildPlan.OutputSpec output = output("rules.txt", Set.of());

        assertMessage(
                () -> plan(List.of(source, source), List.of(output)),
                "source id 重复"
        );
        assertMessage(
                () -> plan(List.of(source), List.of(output, output)),
                "output path 重复"
        );
        assertMessage(
                () -> plan(List.of(source), List.of(output("projected.txt", Set.of("missing")))),
                "引用了未定义的 source"
        );
    }

    @Test
    void validatesSourceLoadingProcessingAndDnsBoundaries() {
        BuildPlan.DnsValidationPolicy disabledDns = new BuildPlan.DnsValidationPolicy(
                false, Duration.ofSeconds(1), 1, Optional.empty());
        assertMessage(() -> new BuildPlan.ProcessingPolicy(
                -1, 0, Set.of(), true, false, disabledDns), "不能小于 0");
        assertMessage(() -> new BuildPlan.ProcessingPolicy(
                20, 10, Set.of(), true, false, disabledDns), "不能大于");
        assertEquals(Set.of("example.com"),
                new BuildPlan.ProcessingPolicy(
                        0, 0, Set.of("EXAMPLE.COM"), true, false, disabledDns
                ).excludedDomains());

        assertMessage(() -> new BuildPlan.SourceLoadingPolicy(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                Duration.ZERO,
                Duration.ofSeconds(1),
                4096,
                4096
        ), "connectTimeout 必须大于 0");
        assertMessage(() -> new BuildPlan.SourceLoadingPolicy(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1023,
                4096
        ), "localBufferSize");

        assertMessage(() -> new BuildPlan.DnsValidationPolicy(
                true, Duration.ofSeconds(1), 1, Optional.empty()), "必须配置 server");
        assertMessage(() -> new BuildPlan.DnsValidationPolicy(
                false, Duration.ofSeconds(1), 0, Optional.empty()), "1..1024");
        assertMessage(() -> new BuildPlan.DnsValidationPolicy(
                false, Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1), 1, Optional.empty()),
                "不能超过");
        assertMessage(() -> new BuildPlan.DnsServer("127.0.0.1", 0), "1..65535");
    }

    private static BuildPlan plan(
            List<BuildPlan.SourceSpec> sources,
            List<BuildPlan.OutputSpec> outputs
    ) {
        return new BuildPlan(
                sources,
                outputs,
                new BuildPlan.SourceLoadingPolicy(
                        StandardCharsets.UTF_8,
                        StandardCharsets.UTF_8,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        4096,
                        4096
                ),
                new BuildPlan.ProcessingPolicy(
                        0,
                        0,
                        Set.of(),
                        true,
                        false,
                        new BuildPlan.DnsValidationPolicy(
                                false, Duration.ofSeconds(1), 1, Optional.empty())
                ),
                new BuildPlan.LoggingPolicy(false)
        );
    }

    private static BuildPlan.SourceSpec source(String id) {
        return new BuildPlan.SourceSpec(
                id,
                "rules.txt",
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                0
        );
    }

    private static BuildPlan.OutputSpec output(String name, Set<String> sources) {
        return new BuildPlan.OutputSpec(
                Path.of("target", name),
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                "",
                "",
                sources
        );
    }

    private static void assertMessage(Runnable action, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains(expected), error::getMessage);
    }
}

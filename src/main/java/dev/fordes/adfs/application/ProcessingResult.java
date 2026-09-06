package dev.fordes.adfs.application;

import java.nio.file.Path;
import java.util.Map;

import dev.fordes.adfs.report.DnsMetrics;
import dev.fordes.adfs.report.OutputMetrics;

public record ProcessingResult(
        long inputs,
        long semanticRules,
        long opaqueRules,
        long uniqueRules,
        long duplicateRules,
        int hashEntries,
        int hashCapacity,
        int hashCollisions,
        DnsMetrics dns,
        Map<Path, OutputMetrics> outputs,
        long elapsedMillis) {

    public ProcessingResult {
        outputs = Map.copyOf(outputs);
    }
}

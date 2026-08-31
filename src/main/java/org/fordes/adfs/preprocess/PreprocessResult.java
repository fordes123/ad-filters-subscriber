package org.fordes.adfs.preprocess;

import java.util.List;
import java.util.Objects;

public record PreprocessResult(
        List<PreprocessedLine> logicalLines,
        List<PreprocessorDiagnostic> diagnostics
) {

    public PreprocessResult {
        Objects.requireNonNull(logicalLines, "logicalLines 不能为空");
        Objects.requireNonNull(diagnostics, "diagnostics 不能为空");
        logicalLines = List.copyOf(logicalLines);
        diagnostics = List.copyOf(diagnostics);
    }
}

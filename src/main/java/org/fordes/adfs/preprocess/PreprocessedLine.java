package org.fordes.adfs.preprocess;

import org.fordes.adfs.syntax.LineSlice;

import java.util.Objects;

public record PreprocessedLine(
        LineSlice line,
        long physicalStartLine
) {

    public PreprocessedLine {
        Objects.requireNonNull(line, "line 不能为空");
        if (physicalStartLine < 1) {
            throw new IllegalArgumentException("physicalStartLine 必须大于 0");
        }
    }
}

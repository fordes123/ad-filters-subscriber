package org.fordes.adfs.preprocess;

import java.util.Objects;

public record PreprocessorDiagnostic(String code, long physicalLine, String message) {

    public PreprocessorDiagnostic {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (physicalLine < 1) {
            throw new IllegalArgumentException("physicalLine 必须大于 0");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}

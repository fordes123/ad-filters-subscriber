package org.fordes.adfs.syntax;

import java.util.Objects;

public record ParseDiagnostic(String code, int offset, String message) {

    public ParseDiagnostic {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(message, "message 不能为空");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能小于 0");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}

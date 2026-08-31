package org.fordes.adfs.syntax.classifier;

import org.fordes.adfs.syntax.Span;

import java.util.Objects;
import java.util.Optional;

public record RuleClassification(
        RuleKind kind,
        int contentStart,
        Optional<SeparatorKind> separatorKind,
        Optional<Span> separator
) {

    public RuleClassification {
        Objects.requireNonNull(kind, "kind 不能为空");
        if (contentStart < 0) {
            throw new IllegalArgumentException("contentStart 不能小于 0");
        }
        Objects.requireNonNull(separatorKind, "separatorKind 不能为空");
        Objects.requireNonNull(separator, "separator 不能为空");
        if (separatorKind.isPresent() != separator.isPresent()) {
            throw new IllegalArgumentException("separatorKind 与 separator 必须同时存在或同时缺失");
        }
    }
}

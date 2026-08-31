package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.Span;

import java.util.Objects;
import java.util.Optional;

public record NetworkModifierAst(
        Span source,
        Span name,
        Optional<Span> value,
        boolean negated
) {

    public NetworkModifierAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(value, "value 不能为空");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("modifier name 不能为空");
        }
        if (name.start() < source.start() || name.end() > source.end()) {
            throw new IllegalArgumentException("modifier name 必须位于 source 内");
        }
        value.ifPresent(span -> {
            if (span.start() < source.start() || span.end() > source.end()) {
                throw new IllegalArgumentException("modifier value 必须位于 source 内");
            }
        });
    }
}

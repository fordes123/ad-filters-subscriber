package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;
import java.util.Optional;

final class AstValidation {

    private AstValidation() {
    }

    static void requireExtendedFields(
            LineSlice source,
            DialectProfile dialect,
            ExtendedAction action,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(nonBasicModifiers, "nonBasicModifiers 不能为空");
        nonBasicModifiers.ifPresent(span -> requireWithin(source, span, "nonBasicModifiers"));
        requireWithin(source, domains, "domains");
        requireNonEmpty(source, separator, "separator");
        requireNonEmpty(source, body, "body");
        if (domains.end() != separator.start() || separator.end() != body.start()) {
            throw new IllegalArgumentException("domains、separator 与 body 必须连续排列");
        }
        nonBasicModifiers.ifPresent(span -> {
            if (span.end() != domains.start()) {
                throw new IllegalArgumentException("nonBasicModifiers 必须紧邻 domains");
            }
        });
    }

    static void requireNonEmpty(LineSlice source, Span span, String field) {
        requireWithin(source, span, field);
        if (span.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    static void requireWithin(LineSlice source, Span span, String field) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(span, field + " 不能为空");
        if (span.end() > source.length()) {
            throw new IllegalArgumentException(field + " 超出原始规则范围");
        }
    }
}

package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;
import java.util.Optional;

public record PreprocessorDirectiveAst(
        LineSlice source,
        DialectProfile dialect,
        Span name,
        Optional<Span> value
) implements RuleAst {

    public PreprocessorDirectiveAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        AstValidation.requireNonEmpty(source, name, "name");
        Objects.requireNonNull(value, "value 不能为空");
        value.ifPresent(span -> AstValidation.requireWithin(source, span, "value"));
    }
}

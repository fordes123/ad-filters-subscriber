package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;
import java.util.Optional;

public record ExtensionAst(
        LineSlice source,
        DialectProfile dialect,
        ExtendedAction action,
        ExtensionKind kind,
        Optional<Span> nonBasicModifiers,
        Span domains,
        Span separator,
        Span body
) implements RuleAst {

    public ExtensionAst {
        AstValidation.requireExtendedFields(
                source, dialect, action, nonBasicModifiers, domains, separator, body);
        Objects.requireNonNull(kind, "kind 不能为空");
    }
}

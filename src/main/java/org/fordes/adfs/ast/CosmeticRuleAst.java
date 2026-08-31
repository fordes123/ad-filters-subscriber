package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;
import java.util.Optional;

public record CosmeticRuleAst(
        LineSlice source,
        DialectProfile dialect,
        ExtendedAction action,
        CosmeticSyntax syntax,
        Optional<Span> nonBasicModifiers,
        Span domains,
        Span separator,
        Span body
) implements RuleAst {

    public CosmeticRuleAst {
        AstValidation.requireExtendedFields(
                source, dialect, action, nonBasicModifiers, domains, separator, body);
        Objects.requireNonNull(syntax, "syntax 不能为空");
    }
}

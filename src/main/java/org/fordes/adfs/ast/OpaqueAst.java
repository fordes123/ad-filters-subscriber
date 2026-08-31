package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.classifier.RuleKind;

import java.util.Objects;

public record OpaqueAst(
        LineSlice source,
        DialectProfile dialect,
        RuleKind kind
) implements RuleAst {

    public OpaqueAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        Objects.requireNonNull(kind, "kind 不能为空");
    }
}

package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;

public record CommentAst(LineSlice source, DialectProfile dialect, Span body) implements RuleAst {

    public CommentAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        AstValidation.requireWithin(source, body, "body");
    }
}

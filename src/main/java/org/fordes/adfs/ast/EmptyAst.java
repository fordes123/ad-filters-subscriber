package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.Objects;

public record EmptyAst(LineSlice source, DialectProfile dialect) implements RuleAst {

    public EmptyAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
    }
}

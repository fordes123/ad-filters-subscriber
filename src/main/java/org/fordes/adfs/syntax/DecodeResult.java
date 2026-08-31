package org.fordes.adfs.syntax;

import org.fordes.adfs.ast.RuleAst;

import java.util.Objects;

public sealed interface DecodeResult<A extends RuleAst>
        permits DecodeResult.Decoded, DecodeResult.Invalid {

    record Decoded<A extends RuleAst>(A ast) implements DecodeResult<A> {
        public Decoded {
            Objects.requireNonNull(ast, "ast 不能为空");
        }
    }

    record Invalid<A extends RuleAst>(LineSlice source, ParseDiagnostic diagnostic)
            implements DecodeResult<A> {
        public Invalid {
            Objects.requireNonNull(source, "source 不能为空");
            Objects.requireNonNull(diagnostic, "diagnostic 不能为空");
        }
    }
}

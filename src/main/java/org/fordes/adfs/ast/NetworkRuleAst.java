package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.DialectProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NetworkRuleAst(
        LineSlice source,
        DialectProfile dialect,
        NetworkAction action,
        NetworkAnchor leftAnchor,
        boolean rightAnchor,
        boolean regex,
        Span pattern,
        Optional<Span> modifierBlock,
        List<NetworkModifierAst> modifiers
) implements RuleAst {

    public NetworkRuleAst {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(leftAnchor, "leftAnchor 不能为空");
        Objects.requireNonNull(pattern, "pattern 不能为空");
        Objects.requireNonNull(modifierBlock, "modifierBlock 不能为空");
        Objects.requireNonNull(modifiers, "modifiers 不能为空");
        if (pattern.end() > source.length()) {
            throw new IllegalArgumentException("pattern 超出原始规则范围");
        }
        modifierBlock.ifPresent(span -> {
            if (span.end() > source.length()) {
                throw new IllegalArgumentException("modifierBlock 超出原始规则范围");
            }
        });
        modifiers = List.copyOf(modifiers);
    }
}

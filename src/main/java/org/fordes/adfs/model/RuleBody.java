package org.fordes.adfs.model;

import org.fordes.adfs.ast.NetworkAction;
import org.fordes.adfs.ast.NetworkAnchor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 规则的唯一内部表示。
 *
 * <p>公共格式只能使用 {@link Canonical}；无法安全抽象到公共格式的
 * Adblock 规则保留为 {@link AdblockNetwork} 或 {@link Extended}，未知规则
 * 则使用 {@link Opaque}。这样不会用多个互斥 Optional 模拟联合类型。</p>
 */
public sealed interface RuleBody
        permits RuleBody.Canonical, RuleBody.AdblockNetwork, RuleBody.Extended, RuleBody.Opaque {

    default Optional<CanonicalRule> canonicalRule() {
        return switch (this) {
            case Canonical canonical -> Optional.of(canonical.value());
            case AdblockNetwork network -> network.portable();
            case Extended ignored -> Optional.empty();
            case Opaque ignored -> Optional.empty();
        };
    }

    record Canonical(CanonicalRule value) implements RuleBody {
        public Canonical {
            Objects.requireNonNull(value, "canonical rule 不能为空");
        }
    }

    record Extended(AdblockExtendedRule value) implements RuleBody {
        public Extended {
            Objects.requireNonNull(value, "extended rule 不能为空");
        }
    }

    /**
     * 已经完成一次 Adblock 网络规则解析的快照。
     * prefix/suffix 是 pattern 两侧的原始文本，用于在方言转换时保留锚点和
     * 未改变的语法；modifier 同时保存完整原文和拆分后的值，避免再次解析 raw。
     */
    record AdblockNetwork(
            NetworkAction action,
            NetworkAnchor leftAnchor,
            boolean rightAnchor,
            boolean regex,
            String prefix,
            String pattern,
            String suffix,
            List<Modifier> modifiers,
            Optional<CanonicalRule> portable
    ) implements RuleBody {
        public AdblockNetwork {
            Objects.requireNonNull(action, "network action 不能为空");
            Objects.requireNonNull(leftAnchor, "network leftAnchor 不能为空");
            Objects.requireNonNull(prefix, "network prefix 不能为空");
            Objects.requireNonNull(pattern, "network pattern 不能为空");
            Objects.requireNonNull(suffix, "network suffix 不能为空");
            Objects.requireNonNull(modifiers, "network modifiers 不能为空");
            Objects.requireNonNull(portable, "network portable 不能为空");
            if (pattern.isEmpty()
                    && (leftAnchor != NetworkAnchor.NONE || rightAnchor || regex || modifiers.isEmpty())) {
                throw new IllegalArgumentException(
                        "空 network pattern 必须使用至少一个 modifier，且不得包含锚点或正则"
                );
            }
            modifiers = List.copyOf(modifiers);
        }

        public String unchangedPattern() {
            return prefix + pattern + (rightAnchor ? "|" : "") + suffix;
        }

        public record Modifier(
                String source,
                String name,
            Optional<String> value,
                boolean negated
        ) {
            public Modifier {
                Objects.requireNonNull(source, "modifier source 不能为空");
                Objects.requireNonNull(name, "modifier name 不能为空");
                Objects.requireNonNull(value, "modifier value 不能为空");
                if (source.isBlank() || name.isBlank()) {
                    throw new IllegalArgumentException("modifier source/name 不能为空");
                }
            }
        }
    }

    record Opaque(String kind) implements RuleBody {
        public Opaque {
            Objects.requireNonNull(kind, "opaque kind 不能为空");
            if (kind.isBlank()) {
                throw new IllegalArgumentException("opaque kind 不能为空");
            }
        }
    }
}

package org.fordes.adfs.model;

import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.RuleProfile;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.util.Objects;

/**
 * 带来源元数据的规则记录。
 *
 * <p>构建管线内部以 {@link RuleBody} 作为唯一规则体。</p>
 */
public record RuleRecord(
        String sourceId,
        RuleProfile sourceProfile,
        String raw,
        RuleBody body
) {

    public RuleRecord {
        Objects.requireNonNull(sourceId, "sourceId 不能为空");
        Objects.requireNonNull(sourceProfile, "sourceProfile 不能为空");
        Objects.requireNonNull(raw, "raw 不能为空");
        Objects.requireNonNull(body, "body 不能为空");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("规则必须保留原始文本");
        }
    }

    public RuleRecord(
            String sourceId,
            RuleFormat sourceFormat,
            DialectProfile sourceDialect,
            ClashDialect sourceClashDialect,
            String raw,
            RuleBody body,
            SourceSyntax sourceSyntax
    ) {
        this(
                sourceId,
                RuleProfile.of(sourceFormat, sourceDialect, sourceClashDialect),
                raw,
                body
        );
        Objects.requireNonNull(sourceSyntax, "sourceSyntax 不能为空");
        if (sourceSyntax() != sourceSyntax) {
            throw new IllegalArgumentException("规则体与 sourceSyntax 不一致");
        }
    }

    public RuleFormat sourceFormat() {
        return sourceProfile.format();
    }

    public DialectProfile sourceDialect() {
        return sourceProfile instanceof RuleProfile.Adblock adblock
                ? adblock.dialect()
                : DialectProfile.ADBLOCK_BASE;
    }

    public ClashDialect sourceClashDialect() {
        return sourceProfile instanceof RuleProfile.Clash clash
                ? clash.dialect()
                : ClashDialect.CLASSICAL;
    }

    public SourceSyntax sourceSyntax() {
        return switch (body) {
            case RuleBody.Canonical ignored -> SourceSyntax.CANONICAL;
            case RuleBody.AdblockNetwork ignored -> SourceSyntax.NETWORK;
            case RuleBody.Extended extended -> SourceSyntax.valueOf(extended.value().syntax().name());
            case RuleBody.Opaque opaque -> SourceSyntax.fromName(opaque.kind());
        };
    }

    public enum SourceSyntax {
        CANONICAL("canonical"),
        NETWORK("network"),
        COSMETIC("cosmetic"),
        ADGUARD_EXTENDED_COSMETIC("adguard-extended-cosmetic"),
        CSS_INJECTION("css-injection"),
        UBO_SCRIPTLET("ubo-scriptlet"),
        ADGUARD_SCRIPTLET("adguard-scriptlet"),
        ABP_SNIPPET("abp-snippet"),
        UBO_HTML("ubo-html"),
        ADGUARD_HTML("adguard-html"),
        ADGUARD_JAVASCRIPT("adguard-javascript"),
        DIALECT_SPECIFIC_EXTENSION("dialect-specific-extension"),
        CLASH_CLASSICAL("clash-classical"),
        OPAQUE("opaque");

        public final String name;

        SourceSyntax(String name) {
            this.name = name;
        }

        private static SourceSyntax fromName(String name) {
            for (SourceSyntax syntax : values()) {
                if (syntax.name.equals(name)) {
                    return syntax;
                }
            }
            return OPAQUE;
        }
    }
}

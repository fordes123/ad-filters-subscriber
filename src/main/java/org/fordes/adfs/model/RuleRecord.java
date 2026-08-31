package org.fordes.adfs.model;

import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.util.Objects;
import java.util.Optional;

public record RuleRecord(
        String sourceId,
        RuleFormat sourceFormat,
        DialectProfile sourceDialect,
        ClashDialect sourceClashDialect,
        String raw,
        Optional<CanonicalRule> canonical,
        Optional<AdblockExtendedRule> extended,
        SourceSyntax sourceSyntax
) {

    public RuleRecord(
            String sourceId,
            RuleFormat sourceFormat,
            DialectProfile sourceDialect,
            ClashDialect sourceClashDialect,
            String raw,
            Optional<CanonicalRule> canonical,
            SourceSyntax sourceSyntax
    ) {
        this(
                sourceId,
                sourceFormat,
                sourceDialect,
                sourceClashDialect,
                raw,
                canonical,
                Optional.empty(),
                sourceSyntax
        );
    }

    public RuleRecord {
        Objects.requireNonNull(sourceId, "sourceId 不能为空");
        Objects.requireNonNull(sourceFormat, "sourceFormat 不能为空");
        Objects.requireNonNull(sourceDialect, "sourceDialect 不能为空");
        Objects.requireNonNull(sourceClashDialect, "sourceClashDialect 不能为空");
        Objects.requireNonNull(raw, "raw 不能为空");
        Objects.requireNonNull(canonical, "canonical 不能为空");
        Objects.requireNonNull(extended, "extended 不能为空");
        Objects.requireNonNull(sourceSyntax, "sourceSyntax 不能为空");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("规则必须保留原始文本");
        }
        if (canonical.isPresent() && extended.isPresent()) {
            throw new IllegalArgumentException("规则不能同时包含 canonical 和 extended 表示");
        }
    }

    public enum SourceSyntax {
        CANONICAL,
        NETWORK,
        COSMETIC,
        ADGUARD_EXTENDED_COSMETIC,
        CSS_INJECTION,
        UBO_SCRIPTLET,
        ADGUARD_SCRIPTLET,
        ABP_SNIPPET,
        UBO_HTML,
        ADGUARD_HTML,
        ADGUARD_JAVASCRIPT,
        DIALECT_SPECIFIC_EXTENSION,
        CLASH_CLASSICAL,
        OPAQUE
    }
}

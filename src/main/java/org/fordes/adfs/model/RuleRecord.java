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
    }
}

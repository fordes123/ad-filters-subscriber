package org.fordes.adfs.model;

import java.util.Objects;
import java.util.Optional;

public record CanonicalRule(
        MatchType matchType,
        String value,
        Action action,
        Optional<String> destination,
        long featureMask
) {

    public static final long FEATURE_IMPORTANT = 1L;
    public static final long FEATURE_ALL = 1L << 1;
    private static final long SUPPORTED_FEATURES = FEATURE_IMPORTANT | FEATURE_ALL;

    public CanonicalRule {
        Objects.requireNonNull(matchType, "matchType 不能为空");
        Objects.requireNonNull(value, "value 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(destination, "destination 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("canonical rule value 不能为空");
        }
        if (action == Action.REWRITE && destination.isEmpty()) {
            throw new IllegalArgumentException("rewrite rule 必须提供 destination");
        }
        if (action != Action.REWRITE && destination.isPresent()) {
            throw new IllegalArgumentException("非 rewrite rule 不得提供 destination");
        }
        if ((featureMask & ~SUPPORTED_FEATURES) != 0) {
            throw new IllegalArgumentException("包含未知规则特性位: " + featureMask);
        }
    }

    public boolean hasFeature(long feature) {
        return (featureMask & feature) != 0;
    }

    public enum MatchType {
        EXACT_DOMAIN("exact-domain"),
        DOMAIN_SUFFIX("domain-suffix"),
        SUBDOMAINS_ONLY("subdomains-only"),
        DOMAIN_KEYWORD("domain-keyword"),
        DOMAIN_REGEX("domain-regex"),
        IP_CIDR("ip-cidr"),
        URL_PATTERN("url-pattern"),
        REGEX("regex");

        public final String name;

        MatchType(String name) {
            this.name = name;
        }
    }

    public enum Action {
        BLOCK("block"),
        ALLOW("allow"),
        REWRITE("rewrite");

        public final String name;

        Action(String name) {
            this.name = name;
        }
    }
}

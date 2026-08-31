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
    }

    public enum MatchType {
        EXACT_DOMAIN,
        DOMAIN_SUFFIX,
        SUBDOMAINS_ONLY,
        DOMAIN_KEYWORD,
        DOMAIN_REGEX,
        IP_CIDR,
        URL_PATTERN,
        REGEX
    }

    public enum Action {
        BLOCK,
        ALLOW,
        REWRITE
    }
}

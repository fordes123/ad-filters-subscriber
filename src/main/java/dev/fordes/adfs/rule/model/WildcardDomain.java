package dev.fordes.adfs.rule.model;

import java.util.Locale;

public record WildcardDomain(String value) implements DomainPattern {

    public WildcardDomain {
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.indexOf('*') < 0) {
            throw new IllegalArgumentException("通配域名必须包含 *");
        }
    }
}

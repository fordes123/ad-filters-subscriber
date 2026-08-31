package org.fordes.adfs.syntax.clash;

import java.util.Locale;

public enum ClashDialect {
    DOMAIN,
    IPCIDR,
    CLASSICAL;

    public static ClashDialect parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Clash 方言不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("未知 Clash 方言: " + value, error);
        }
    }
}

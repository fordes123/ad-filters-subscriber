package org.fordes.adfs.syntax.clash;

public enum ClashDialect {
    DOMAIN("domain"),
    IPCIDR("ipcidr"),
    CLASSICAL("classical");

    public final String name;

    ClashDialect(String name) {
        this.name = name;
    }

    public static ClashDialect parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Clash 方言不能为空");
        }
        String name = value.trim();
        for (ClashDialect dialect : values()) {
            if (dialect.name.equalsIgnoreCase(name)) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("未知 Clash 方言: " + value);
    }
}

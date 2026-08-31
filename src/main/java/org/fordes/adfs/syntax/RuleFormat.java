package org.fordes.adfs.syntax;

public enum RuleFormat {
    EASYLIST,
    DNS,
    HOSTS,
    DNSMASQ,
    SMARTDNS,
    CLASH,
    SING_BOX;

    public static RuleFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rule format 不能为空");
        }
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("未知规则格式: " + value, error);
        }
    }
}

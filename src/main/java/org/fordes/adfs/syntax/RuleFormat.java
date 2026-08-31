package org.fordes.adfs.syntax;

public enum RuleFormat {
    EASYLIST("easylist"),
    DNS("dns"),
    HOSTS("hosts"),
    DNSMASQ("dnsmasq"),
    SMARTDNS("smartdns"),
    CLASH("clash"),
    SING_BOX("sing-box");

    public final String name;

    RuleFormat(String name) {
        this.name = name;
    }

    public static RuleFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rule format 不能为空");
        }
        String name = value.trim();
        for (RuleFormat format : values()) {
            if (format.name.equalsIgnoreCase(name)) {
                return format;
            }
        }
        throw new IllegalArgumentException("未知规则格式: " + value);
    }
}

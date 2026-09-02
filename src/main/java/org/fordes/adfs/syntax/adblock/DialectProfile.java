package org.fordes.adfs.syntax.adblock;

public enum DialectProfile {
    ADBLOCK_BASE("adblock-base"),
    ABP("abp"),
    ADGUARD("adguard"),
    UBO("ubo");

    public final String name;

    DialectProfile(String name) {
        this.name = name;
    }

    public static DialectProfile parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Adblock 方言不能为空");
        }
        String name = value.trim();
        for (DialectProfile profile : values()) {
            if (profile.name.equalsIgnoreCase(name)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("未知 Adblock 方言: " + value);
    }
}

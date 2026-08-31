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
}

package org.fordes.adfs.ast;

public enum ExtensionKind {
    ADGUARD_JAVASCRIPT("adguard-javascript"),
    DIALECT_SPECIFIC_EXTENDED_FILTER("dialect-specific-extended-filter");

    public final String name;

    ExtensionKind(String name) {
        this.name = name;
    }
}

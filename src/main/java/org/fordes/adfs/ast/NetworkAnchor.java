package org.fordes.adfs.ast;

public enum NetworkAnchor {
    NONE("none"),
    ADDRESS("address"),
    DOMAIN("domain");

    public final String name;

    NetworkAnchor(String name) {
        this.name = name;
    }
}

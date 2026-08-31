package org.fordes.adfs.ast;

public enum NetworkAction {
    BLOCK("block"),
    ALLOW("allow");

    public final String name;

    NetworkAction(String name) {
        this.name = name;
    }
}

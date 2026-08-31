package org.fordes.adfs.ast;

public enum ExtendedAction {
    APPLY("apply"),
    EXCEPT("except");

    public final String name;

    ExtendedAction(String name) {
        this.name = name;
    }
}

package org.fordes.adfs.ast;

public enum CosmeticSyntax {
    ELEMENT_HIDING("element-hiding"),
    EXTENDED_SELECTOR("extended-selector"),
    EXTENDED_CSS("extended-css"),
    CSS_INJECTION("css-injection");

    public final String name;

    CosmeticSyntax(String name) {
        this.name = name;
    }
}

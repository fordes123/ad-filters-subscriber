package org.fordes.adfs.syntax.classifier;

public enum SeparatorKind {
    UBO_SCRIPTLET_EXCEPTION("ubo-scriptlet-exception"),
    ADGUARD_SCRIPTLET_EXCEPTION("adguard-scriptlet-exception"),
    UBO_HTML_EXCEPTION("ubo-html-exception"),
    EXTENDED_CSS_EXCEPTION("extended-css-exception"),
    EXTENDED_COSMETIC_EXCEPTION("extended-cosmetic-exception"),
    HASH_DOLLAR_EXCEPTION("hash-dollar-exception"),
    HASH_PERCENT_EXCEPTION("hash-percent-exception"),
    ELEMENT_HIDING_EXCEPTION("element-hiding-exception"),
    ADGUARD_HTML_EXCEPTION("adguard-html-exception"),
    UBO_SCRIPTLET("ubo-scriptlet"),
    ADGUARD_SCRIPTLET("adguard-scriptlet"),
    UBO_HTML("ubo-html"),
    EXTENDED_CSS("extended-css"),
    EXTENDED_COSMETIC("extended-cosmetic"),
    HASH_DOLLAR("hash-dollar"),
    HASH_PERCENT("hash-percent"),
    ELEMENT_HIDING("element-hiding"),
    ADGUARD_HTML("adguard-html");

    public final String name;

    SeparatorKind(String name) {
        this.name = name;
    }
}

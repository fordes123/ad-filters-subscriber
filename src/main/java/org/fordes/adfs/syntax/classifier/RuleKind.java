package org.fordes.adfs.syntax.classifier;

public enum RuleKind {
    EMPTY("empty"),
    COMMENT("comment"),
    METADATA("metadata"),
    PREPROCESSOR("preprocessor"),
    NETWORK("network"),
    EXTENDED_FILTER("extended-filter");

    public final String name;

    RuleKind(String name) {
        this.name = name;
    }
}

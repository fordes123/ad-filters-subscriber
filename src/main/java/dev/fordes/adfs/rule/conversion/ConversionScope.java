package dev.fordes.adfs.rule.conversion;

public enum ConversionScope {
    EXACT("exact"),
    EXPANDED("expanded"),
    REDUCED("reduced"),
    MIXED("mixed"),
    UNSUPPORTED("unsupported");

    private final String value;

    ConversionScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

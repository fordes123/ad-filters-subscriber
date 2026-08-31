package org.fordes.adfs.ast;

public enum ScriptletSyntax {
    ABP_SNIPPET("abp-snippet"),
    UBO_SCRIPTLET("ubo-scriptlet"),
    ADGUARD_SCRIPTLET("adguard-scriptlet");

    public final String name;

    ScriptletSyntax(String name) {
        this.name = name;
    }
}

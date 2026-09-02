package org.fordes.adfs.model;

import java.util.Objects;
import java.util.Optional;

public record AdblockExtendedRule(
        Syntax syntax,
        Action action,
        Optional<String> nonBasicModifiers,
        String domains,
        String body,
        Optional<String> scriptletName
) {

    public AdblockExtendedRule {
        Objects.requireNonNull(syntax, "syntax 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(nonBasicModifiers, "nonBasicModifiers 不能为空");
        Objects.requireNonNull(domains, "domains 不能为空");
        Objects.requireNonNull(body, "body 不能为空");
        Objects.requireNonNull(scriptletName, "scriptletName 不能为空");
        if (body.isBlank()) {
            throw new IllegalArgumentException("扩展规则 body 不能为空");
        }
        if (scriptletName.isPresent() && scriptletName.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("scriptletName 不能为空");
        }
        if (!syntax.scriptlet() && scriptletName.isPresent()) {
            throw new IllegalArgumentException("非 scriptlet 规则不得包含 scriptletName");
        }
    }

    public enum Syntax {
        COSMETIC("cosmetic", false),
        ADGUARD_EXTENDED_COSMETIC("adguard-extended-cosmetic", false),
        CSS_INJECTION("css-injection", false),
        UBO_SCRIPTLET("ubo-scriptlet", true),
        ADGUARD_SCRIPTLET("adguard-scriptlet", true),
        ABP_SNIPPET("abp-snippet", true),
        UBO_HTML("ubo-html", false),
        ADGUARD_HTML("adguard-html", false),
        ADGUARD_JAVASCRIPT("adguard-javascript", false),
        DIALECT_SPECIFIC_EXTENSION("dialect-specific-extension", false);

        public final String name;
        private final boolean scriptlet;

        Syntax(String name, boolean scriptlet) {
            this.name = name;
            this.scriptlet = scriptlet;
        }

        public boolean scriptlet() {
            return scriptlet;
        }
    }

    public enum Action {
        APPLY("apply"),
        EXCEPT("except");

        public final String name;

        Action(String name) {
            this.name = name;
        }
    }
}

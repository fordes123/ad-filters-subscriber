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
        COSMETIC(false),
        ADGUARD_EXTENDED_COSMETIC(false),
        CSS_INJECTION(false),
        UBO_SCRIPTLET(true),
        ADGUARD_SCRIPTLET(true),
        ABP_SNIPPET(true),
        UBO_HTML(false),
        ADGUARD_HTML(false),
        ADGUARD_JAVASCRIPT(false),
        DIALECT_SPECIFIC_EXTENSION(false),
        OPAQUE(false);

        private final boolean scriptlet;

        Syntax(boolean scriptlet) {
            this.scriptlet = scriptlet;
        }

        public boolean scriptlet() {
            return scriptlet;
        }
    }

    public enum Action {
        APPLY,
        EXCEPT
    }
}

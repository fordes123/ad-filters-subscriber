package dev.fordes.adfs.format.adblock;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.fordes.adfs.config.RuleDialect;

public record AdblockDialectDefinition(
        RuleDialect dialect,
        Set<String> builtInTokens,
        Map<String, AdblockCapability> cosmeticOperators,
        Map<String, AdblockCapability> options) {

    private static final Set<String> COMMON_OPTIONS = Set.of(
            "script", "image", "stylesheet", "font", "media", "object", "xmlhttprequest", "subdocument",
            "document", "popup", "websocket", "ping", "other", "domain", "third-party", "match-case",
            "important", "badfilter", "first-party", "xhr");
    private static final Set<String> VALUE_MODIFIERS = Set.of(
            "redirect", "redirect-rule", "removeparam", "csp", "permissions", "replace", "urltransform");

    public AdblockDialectDefinition {
        builtInTokens = Set.copyOf(builtInTokens);
        cosmeticOperators = Map.copyOf(cosmeticOperators);
        options = Map.copyOf(options);
    }

    public static AdblockDialectDefinition forDialect(RuleDialect dialect) {
        Map<String, AdblockCapability> operators = switch (dialect) {
            case CORE, ABP -> Map.of("#@#", AdblockCapability.SEMANTIC, "##", AdblockCapability.SEMANTIC);
            case ADGUARD -> Map.ofEntries(
                    Map.entry("#@$?#", AdblockCapability.SEMANTIC),
                    Map.entry("#$?#", AdblockCapability.SEMANTIC),
                    Map.entry("#@?#", AdblockCapability.SEMANTIC), Map.entry("#?#", AdblockCapability.SEMANTIC),
                    Map.entry("#@%#", AdblockCapability.SEMANTIC), Map.entry("#%#", AdblockCapability.SEMANTIC),
                    Map.entry("#@$#", AdblockCapability.SEMANTIC), Map.entry("#$#", AdblockCapability.SEMANTIC),
                    Map.entry("$@$", AdblockCapability.SEMANTIC), Map.entry("$$", AdblockCapability.SEMANTIC),
                    Map.entry("#@#", AdblockCapability.SEMANTIC), Map.entry("##", AdblockCapability.SEMANTIC));
            case UBO -> Map.of(
                    "#@?#", AdblockCapability.SEMANTIC, "#?#", AdblockCapability.SEMANTIC,
                    "#@%#", AdblockCapability.SEMANTIC, "#%#", AdblockCapability.SEMANTIC,
                    "$@$", AdblockCapability.SEMANTIC, "$$", AdblockCapability.SEMANTIC,
                    "#@#", AdblockCapability.SEMANTIC, "##", AdblockCapability.SEMANTIC);
            default -> throw new IllegalArgumentException("不是 Adblock 方言: " + dialect.value());
        };
        Map<String, AdblockCapability> options = new HashMap<>();
        COMMON_OPTIONS.forEach(option -> options.put(option, AdblockCapability.SEMANTIC));
        VALUE_MODIFIERS.forEach(option -> options.put(option,
                dialect == RuleDialect.CORE ? AdblockCapability.PASSTHROUGH : AdblockCapability.SEMANTIC));
        if (dialect == RuleDialect.ABP) {
            options.put("redirect-rule", AdblockCapability.INVALID);
            options.put("replace", AdblockCapability.INVALID);
            options.put("urltransform", AdblockCapability.INVALID);
        }
        Set<String> tokens = switch (dialect) {
            case CORE -> Set.of("core");
            case ADGUARD -> Set.of("adguard");
            case ABP -> Set.of("abp");
            case UBO -> Set.of("ubo", "ext_ublock");
            default -> throw new IllegalArgumentException("不是 Adblock 方言: " + dialect.value());
        };
        return new AdblockDialectDefinition(dialect, tokens, operators, options);
    }

    public AdblockCapability option(String name) {
        return options.getOrDefault(name, AdblockCapability.PASSTHROUGH);
    }

    public AdblockCapability cosmetic(String operator) {
        return cosmeticOperators.getOrDefault(operator, AdblockCapability.INVALID);
    }
}

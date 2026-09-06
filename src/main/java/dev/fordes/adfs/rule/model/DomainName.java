package dev.fordes.adfs.rule.model;

import java.net.IDN;
import java.util.Locale;

import dev.fordes.adfs.error.RuleProcessingException;

public record DomainName(String value) implements Comparable<DomainName> {

    public DomainName {
        try {
            value = IDN.toASCII(value.strip().replaceFirst("\\.$", ""), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new RuleProcessingException("域名非法: value=" + value, exception);
        }
        if (value.isEmpty() || value.length() > 253 || value.startsWith(".") || value.endsWith(".")) {
            throw new RuleProcessingException("域名长度或边界非法: value=" + value);
        }
    }

    @Override
    public int compareTo(DomainName other) {
        return value.compareTo(other.value);
    }
}

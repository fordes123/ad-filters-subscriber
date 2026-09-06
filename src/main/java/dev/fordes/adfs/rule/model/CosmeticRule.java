package dev.fordes.adfs.rule.model;

import java.util.List;

import dev.fordes.adfs.error.RuleProcessingException;

public record CosmeticRule(
        List<DomainConstraint> domains,
        boolean exception,
        CosmeticOperator operator,
        String body) implements Rule {

    public CosmeticRule {
        domains = List.copyOf(domains);
        body = body.strip();
        if (body.isEmpty()) {
            throw new RuleProcessingException("元素规则主体不得为空");
        }
    }
}

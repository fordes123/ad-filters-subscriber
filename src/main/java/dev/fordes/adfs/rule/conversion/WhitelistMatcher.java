package dev.fordes.adfs.rule.conversion;

import dev.fordes.adfs.rule.model.AllOf;
import dev.fordes.adfs.rule.model.AnyOf;
import dev.fordes.adfs.rule.model.DomainMatch;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainPattern;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.IpCidrMatch;
import dev.fordes.adfs.rule.model.KeywordDomain;
import dev.fordes.adfs.rule.model.MatchExpression;
import dev.fordes.adfs.rule.model.NetworkMatch;
import dev.fordes.adfs.rule.model.Not;
import dev.fordes.adfs.rule.model.PortMatch;
import dev.fordes.adfs.rule.model.ProcessMatch;
import dev.fordes.adfs.rule.model.RegexDomain;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.model.WildcardDomain;

/** 无法证明匹配范围与白名单不相交时，保守视为相交。 */
public final class WhitelistMatcher {

    private WhitelistMatcher() {
    }

    public static boolean intersects(MatchExpression expression, DomainName allowed) {
        return switch (expression) {
            case DomainMatch(var pattern) -> intersects(pattern, allowed);
            case AllOf(var expressions) -> expressions.stream().allMatch(child -> intersects(child, allowed));
            case AnyOf(var expressions) -> expressions.stream().anyMatch(child -> intersects(child, allowed));
            case Not _, IpCidrMatch _, PortMatch _, NetworkMatch _, ProcessMatch _ -> true;
        };
    }

    public static boolean intersects(DomainPattern pattern, DomainName allowed) {
        return switch (pattern) {
            case ExactDomain(var domain) -> isWithin(domain, allowed);
            case SuffixDomain(var domain) -> isWithin(domain, allowed) || isWithin(allowed, domain);
            case KeywordDomain _, RegexDomain _, WildcardDomain _ -> true;
        };
    }

    public static boolean isWithin(DomainName candidate, DomainName parent) {
        return candidate.equals(parent) || candidate.value().endsWith("." + parent.value());
    }
}

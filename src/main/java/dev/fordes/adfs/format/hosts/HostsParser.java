package dev.fordes.adfs.format.hosts;

import java.util.List;

import dev.fordes.adfs.config.EffectiveConfig.InputLimits;
import dev.fordes.adfs.config.EffectiveConfig.RuleConfig;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.format.LineTokens;
import dev.fordes.adfs.format.RuleConsumer;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.format.TextSource;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.HostMappingRule;
import dev.fordes.adfs.rule.model.IpAddress;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;

public final class HostsParser implements RuleParser {

    private final InputLimits limits;
    private final RuleConfig rules;

    public HostsParser(InputLimits limits, RuleConfig rules) {
        this.limits = limits;
        this.rules = rules;
    }

    @Override
    public void parse(SourceSession session, RuleConsumer consumer) {
        TextSource.read(session, limits, line -> parseLine(line, consumer));
    }

    private void parseLine(SourceLine line, RuleConsumer consumer) {
        String stripped = line.text().strip();
        if (stripped.isEmpty() || stripped.startsWith("#")) {
            return;
        }
        String text = TextSource.ruleText(line, rules.minLength(), rules.maxLength());
        List<String> tokens = LineTokens.beforeComment(text);
        if (tokens.size() < 2) {
            throw new RuleProcessingException("Hosts 记录至少需要地址和主机名: source="
                    + line.source() + ", line=" + line.lineNumber());
        }
        IpAddress address = IpAddress.parse(tokens.getFirst());
        for (String hostname : tokens.subList(1, tokens.size())) {
            DomainName domain = new DomainName(hostname);
            if (address.isBlockingTarget()) {
                consumer.accept(new DomainRule(new ExactDomain(domain), RuleAction.BLOCK));
            } else {
                consumer.accept(new HostMappingRule(address, domain));
            }
        }
    }
}

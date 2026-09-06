package dev.fordes.adfs.format.dnsmasq;

import java.util.Arrays;
import java.util.List;

import dev.fordes.adfs.config.EffectiveConfig.InputLimits;
import dev.fordes.adfs.config.EffectiveConfig.RuleConfig;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.format.RuleConsumer;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.format.TextSource;
import dev.fordes.adfs.rule.model.DomainEnvelope;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;

public final class DnsmasqParser implements RuleParser {

    private static final String ADDRESS = "address=";
    private static final String LONG_ADDRESS = "--address=";
    private static final String SERVER = "server=";
    private static final String LONG_SERVER = "--server=";
    private final InputLimits limits;
    private final RuleConfig rules;

    public DnsmasqParser(InputLimits limits, RuleConfig rules) {
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
        String body;
        if (text.startsWith(ADDRESS)) {
            body = text.substring(ADDRESS.length());
        } else if (text.startsWith(LONG_ADDRESS)) {
            body = text.substring(LONG_ADDRESS.length());
        } else if (text.startsWith(SERVER) || text.startsWith(LONG_SERVER)) {
            consumer.accept(new OpaqueRule(RuleType.DNSMASQ, RuleDialect.NONE, DomainEnvelope.UNKNOWN, text));
            return;
        } else {
            throw new RuleProcessingException("不接受非规则型 dnsmasq 配置: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        if (!body.startsWith("/")) {
            throw new RuleProcessingException("dnsmasq address 缺少域名段: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        List<String> segments = Arrays.asList(body.substring(1).split("/", -1));
        if (segments.size() < 2) {
            throw new RuleProcessingException("dnsmasq address 结构非法: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        String target = segments.getLast();
        if (!(target.isEmpty() || target.equals("#") || target.equals("0.0.0.0") || target.equals("::"))) {
            consumer.accept(new OpaqueRule(RuleType.DNSMASQ, RuleDialect.NONE, DomainEnvelope.UNKNOWN, text));
            return;
        }
        for (String domain : segments.subList(0, segments.size() - 1)) {
            if (domain.isEmpty() || domain.equals("#")) {
                throw new RuleProcessingException("dnsmasq 全局匹配不能安全转换为域名规则: source="
                        + line.source() + ", line=" + line.lineNumber());
            }
            consumer.accept(new DomainRule(new SuffixDomain(new DomainName(domain)), RuleAction.BLOCK));
        }
    }
}

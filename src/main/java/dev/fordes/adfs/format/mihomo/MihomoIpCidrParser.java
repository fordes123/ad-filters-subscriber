package dev.fordes.adfs.format.mihomo;

import dev.fordes.adfs.config.EffectiveConfig.InputLimits;
import dev.fordes.adfs.config.EffectiveConfig.RuleConfig;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.format.RuleConsumer;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.format.TextSource;
import dev.fordes.adfs.rule.model.IpCidr;
import dev.fordes.adfs.rule.model.IpCidrRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;

public final class MihomoIpCidrParser implements RuleParser {

    private final InputLimits limits;
    private final RuleConfig rules;
    private boolean yaml;
    private boolean decided;

    public MihomoIpCidrParser(InputLimits limits, RuleConfig rules) {
        this.limits = limits;
        this.rules = rules;
    }

    @Override
    public void parse(SourceSession session, RuleConsumer consumer) {
        TextSource.read(session, limits, line -> parseLine(line, consumer));
    }

    private void parseLine(SourceLine line, RuleConsumer consumer) {
        String text = line.text().strip();
        if (text.isEmpty() || text.startsWith("#")) {
            return;
        }
        if (!decided) {
            decided = true;
            yaml = text.equals("payload:");
            if (yaml) {
                return;
            }
        }
        if (yaml) {
            if (!text.startsWith("-")) {
                throw new RuleProcessingException("Mihomo YAML payload 只接受序列项: source=" + line.source()
                        + ", line=" + line.lineNumber());
            }
            text = text.substring(1).strip();
            if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
                text = text.substring(1, text.length() - 1).replace("''", "'");
            }
        }
        text = TextSource.ruleText(new SourceLine(line.source(), line.lineNumber(), text),
                rules.minLength(), rules.maxLength());
        IpCidr cidr = IpCidr.parse(text);
        consumer.accept(new IpCidrRule(cidr.network(), cidr.prefixLength(), RuleAction.BLOCK));
    }
}

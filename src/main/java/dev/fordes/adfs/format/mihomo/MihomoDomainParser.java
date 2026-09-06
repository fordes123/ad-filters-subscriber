package dev.fordes.adfs.format.mihomo;

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
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.model.WildcardDomain;
import dev.fordes.adfs.source.SourceLine;
import dev.fordes.adfs.source.SourceSession;

public final class MihomoDomainParser implements RuleParser {

    private final InputLimits limits;
    private final RuleConfig rules;
    private ContainerState container = ContainerState.UNDECIDED;

    public MihomoDomainParser(InputLimits limits, RuleConfig rules) {
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
        if (container == ContainerState.UNDECIDED) {
            container = stripped.equals("payload:") ? ContainerState.YAML : ContainerState.TEXT;
            if (container == ContainerState.YAML) {
                return;
            }
        }
        String value = switch (container) {
            case YAML -> parseYamlItem(line, stripped);
            case TEXT -> stripped;
            case UNDECIDED -> throw new IllegalStateException("容器状态必须已经确定");
        };
        value = TextSource.ruleText(new SourceLine(line.source(), line.lineNumber(), value),
                rules.minLength(), rules.maxLength());
        if (value.indexOf('*') >= 0) {
            consumer.accept(new DomainRule(new WildcardDomain(value), RuleAction.BLOCK));
        } else if (value.startsWith(".")) {
            consumer.accept(new DomainRule(new SuffixDomain(new DomainName(value.substring(1))), RuleAction.BLOCK));
        } else {
            consumer.accept(new DomainRule(new ExactDomain(new DomainName(value)), RuleAction.BLOCK));
        }
    }

    private static String parseYamlItem(SourceLine line, String text) {
        if (!text.startsWith("-")) {
            throw new RuleProcessingException("Mihomo YAML payload 只接受序列项: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        String scalar = text.substring(1).strip();
        if (scalar.length() >= 2 && scalar.startsWith("'") && scalar.endsWith("'")) {
            return scalar.substring(1, scalar.length() - 1).replace("''", "'");
        }
        if (scalar.length() >= 2 && scalar.startsWith("\"") && scalar.endsWith("\"")) {
            return decodeDoubleQuoted(scalar.substring(1, scalar.length() - 1), line);
        }
        if (scalar.indexOf('&') >= 0 || scalar.indexOf('*') >= 0 || scalar.indexOf('!') >= 0) {
            throw new RuleProcessingException("Mihomo YAML 不支持锚点、别名或 tag: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        int comment = scalar.indexOf(" #");
        return comment < 0 ? scalar : scalar.substring(0, comment).stripTrailing();
    }

    private static String decodeDoubleQuoted(String scalar, SourceLine line) {
        StringBuilder decoded = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < scalar.length(); index++) {
            char character = scalar.charAt(index);
            if (escaped) {
                decoded.append(switch (character) {
                    case '\\', '"', '/' -> character;
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> throw new RuleProcessingException("Mihomo YAML 双引号转义不支持: source="
                            + line.source() + ", line=" + line.lineNumber());
                });
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else {
                decoded.append(character);
            }
        }
        if (escaped) {
            throw new RuleProcessingException("Mihomo YAML 双引号转义未结束: source=" + line.source()
                    + ", line=" + line.lineNumber());
        }
        return decoded.toString();
    }

    private enum ContainerState {
        UNDECIDED,
        TEXT,
        YAML
    }
}

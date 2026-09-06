package dev.fordes.adfs.format.singbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import dev.fordes.adfs.config.EffectiveConfig.RuleConfig;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.error.RuleProcessingException;
import dev.fordes.adfs.format.RuleConsumer;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.rule.spool.RuleSpool;
import dev.fordes.adfs.rule.model.AllOf;
import dev.fordes.adfs.rule.model.AnyOf;
import dev.fordes.adfs.rule.model.DomainEnvelope;
import dev.fordes.adfs.rule.model.DomainMatch;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.IpCidr;
import dev.fordes.adfs.rule.model.IpCidrMatch;
import dev.fordes.adfs.rule.model.KeywordDomain;
import dev.fordes.adfs.rule.model.MatchExpression;
import dev.fordes.adfs.rule.model.MatchSide;
import dev.fordes.adfs.rule.model.NetworkMatch;
import dev.fordes.adfs.rule.model.Not;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.PortMatch;
import dev.fordes.adfs.rule.model.ProcessMatch;
import dev.fordes.adfs.rule.model.RegexDomain;
import dev.fordes.adfs.rule.model.RouteRule;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.source.SourceSession;

@Slf4j
public final class SingBoxParser implements RuleParser {

    private static final int MAX_NESTING_DEPTH = 32;
    private static final int MAX_NUMBER_LENGTH = 64;
    private final RuleConfig rules;
    private final JsonFactory factory;

    public SingBoxParser(RuleConfig rules) {
        this.rules = rules;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(rules.maxLength())
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        this.factory = JsonFactory.builder().streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }

    @Override
    public void parse(SourceSession session, RuleConsumer consumer) {
        try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), session.root().input())) {
            if (log.isDebugEnabled()) {
                MDC.remove(RuleSpool.INPUT_RULE);
            }
            require(parser.nextToken(), JsonToken.START_OBJECT, parser, "顶层必须是对象");
            boolean version = false;
            boolean ruleArray = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken(), JsonToken.PROPERTY_NAME, parser, "顶层字段名非法");
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals("version")) {
                    if (value != JsonToken.VALUE_NUMBER_INT) {
                        throw syntax(parser, "version 必须是整数");
                    }
                    int formatVersion = parser.getIntValue();
                    if (formatVersion < 1 || formatVersion > 3) {
                        throw syntax(parser, "不支持的 Sing-box version: value=" + formatVersion);
                    }
                    version = true;
                } else if (field.equals("rules")) {
                    require(value, JsonToken.START_ARRAY, parser, "rules 必须是数组");
                    ruleArray = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        require(parser.currentToken(), JsonToken.START_OBJECT, parser, "rules 元素必须是对象");
                        byte[] object = captureObject(parser);
                        if (log.isDebugEnabled()) {
                            MDC.put(RuleSpool.INPUT_RULE, new String(object, StandardCharsets.UTF_8));
                        }
                        consumer.accept(parseRule(object, 0));
                        if (log.isDebugEnabled()) {
                            MDC.remove(RuleSpool.INPUT_RULE);
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!version || !ruleArray) {
                throw syntax(parser, "顶层缺少 version 或 rules");
            }
            if (parser.nextToken() != null) {
                throw syntax(parser, "顶层对象后存在额外 JSON 内容");
            }
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            log.debug("Sing-box JSON 解析失败:  {} | {} --> {}",
                    MDC.get(RuleSpool.INPUT), MDC.get(RuleSpool.INPUT_RULE), exception.getMessage());
            throw new RuleProcessingException("解析 Sing-box JSON 失败: source="
                    + session.root().description() + ", reason=" + exception.getMessage(), exception);
        } catch (RuleProcessingException exception) {
            log.debug("Sing-box 规则解析失败:  {} | {} --> {}",
                    MDC.get(RuleSpool.INPUT), MDC.get(RuleSpool.INPUT_RULE), exception.getMessage());
            throw exception;
        }
    }

    private byte[] captureObject(JsonParser parser) throws IOException {
        try (RuleBuffer bytes = new RuleBuffer(Math.multiplyExact(rules.maxLength(), 4));
                JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), bytes)) {
            generator.copyCurrentStructure(parser);
            generator.flush();
            byte[] object = bytes.toByteArray();
            int length = new String(object, StandardCharsets.UTF_8).length();
            if (length < rules.minLength() || length > rules.maxLength()) {
                throw syntax(parser, "单条 Sing-box 规则长度越界: length=" + length);
            }
            return object;
        }
    }

    private RuleEntry parseRule(byte[] object, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new RuleProcessingException("Sing-box 逻辑规则嵌套超过上限: max-depth=" + MAX_NESTING_DEPTH);
        }
        List<MatchExpression> expressions = new ArrayList<>();
        List<MatchExpression> destinations = new ArrayList<>();
        List<MatchExpression> sourcePorts = new ArrayList<>();
        List<MatchExpression> destinationPorts = new ArrayList<>();
        List<MatchExpression> nested = null;
        int nestedCount = 0;
        boolean invert = false;
        boolean unsupported = false;
        String type = "default";
        String mode = null;
        try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), object)) {
            require(parser.nextToken(), JsonToken.START_OBJECT, parser, "规则必须是对象");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "domain" -> addDomains(parser, destinations, DomainKind.EXACT);
                    case "domain_suffix" -> addDomains(parser, destinations, DomainKind.SUFFIX);
                    case "domain_keyword" -> addDomains(parser, destinations, DomainKind.KEYWORD);
                    case "domain_regex" -> addDomains(parser, destinations, DomainKind.REGEX);
                    case "source_ip_cidr" -> addCidrs(parser, expressions, MatchSide.SOURCE);
                    case "ip_cidr" -> addCidrs(parser, destinations, MatchSide.DESTINATION);
                    case "source_port" -> addPorts(parser, sourcePorts, MatchSide.SOURCE);
                    case "port" -> addPorts(parser, destinationPorts, MatchSide.DESTINATION);
                    case "source_port_range" -> addPortRanges(parser, sourcePorts, MatchSide.SOURCE);
                    case "port_range" -> addPortRanges(parser, destinationPorts, MatchSide.DESTINATION);
                    case "network" -> addNetworks(parser, expressions);
                    case "process_name" -> addProcesses(parser, expressions, ProcessMatch.ProcessField.NAME);
                    case "process_path" -> addProcesses(parser, expressions, ProcessMatch.ProcessField.PATH);
                    case "package_name" -> addProcesses(parser, expressions, ProcessMatch.ProcessField.PACKAGE);
                    case "invert" -> invert = parser.getBooleanValue();
                    case "type" -> type = requireString(parser, "type 必须是字符串");
                    case "mode" -> mode = requireString(parser, "mode 必须是字符串");
                    case "rules" -> {
                        require(value, JsonToken.START_ARRAY, parser, "logical rules 必须是数组");
                        nested = new ArrayList<>();
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            require(parser.currentToken(), JsonToken.START_OBJECT, parser,
                                    "logical rules 元素必须是对象");
                            RuleEntry child = parseRule(captureObject(parser), depth + 1);
                            nestedCount++;
                            if (child instanceof RouteRule route) {
                                nested.add(route.expression());
                            } else {
                                unsupported = true;
                            }
                        }
                    }
                    default -> {
                        unsupported = true;
                        parser.skipChildren();
                    }
                }
            }
        }
        for (List<MatchExpression> group : List.of(destinations, sourcePorts, destinationPorts)) {
            if (!group.isEmpty()) {
                expressions.add(group.size() == 1 ? group.getFirst() : new AnyOf(group));
            }
        }
        if (type.equals("logical")) {
            if (!expressions.isEmpty() || nested == null || nestedCount == 0 || mode == null) {
                throw new RuleProcessingException("Sing-box logical 规则字段组合非法");
            }
            if (!mode.equals("and") && !mode.equals("or")) {
                throw new RuleProcessingException("Sing-box logical mode 非法: value=" + mode);
            }
        } else if (type.equals("default")) {
            if (mode != null || nested != null) {
                throw new RuleProcessingException("Sing-box default 规则不能包含 mode 或 rules");
            }
            if (expressions.isEmpty() && !unsupported) {
                throw new RuleProcessingException("Sing-box 规则没有匹配字段");
            }
        } else {
            throw new RuleProcessingException("Sing-box 规则 type 非法: value=" + type);
        }
        if (unsupported) {
            return new OpaqueRule(RuleType.SING_BOX, RuleDialect.NONE, DomainEnvelope.UNKNOWN,
                    new String(object, StandardCharsets.UTF_8));
        }
        MatchExpression expression;
        if (type.equals("logical")) {
            expression = mode.equals("and") ? new AllOf(nested) : new AnyOf(nested);
        } else {
            expression = expressions.size() == 1 ? expressions.getFirst() : new AllOf(expressions);
        }
        return new RouteRule(invert ? new Not(expression) : expression);
    }

    private static String requireString(JsonParser parser, String message) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw syntax(parser, message);
        }
        return parser.getString();
    }

    private static void addDomains(JsonParser parser, List<MatchExpression> target, DomainKind kind) throws IOException {
        List<String> values = strings(parser);
        List<MatchExpression> matches = values.stream().map(value -> (MatchExpression) new DomainMatch(switch (kind) {
            case EXACT -> new ExactDomain(new DomainName(value));
            case SUFFIX -> new SuffixDomain(new DomainName(value));
            case KEYWORD -> new KeywordDomain(value);
            case REGEX -> new RegexDomain(value);
        })).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static void addCidrs(JsonParser parser, List<MatchExpression> target, MatchSide side) throws IOException {
        List<MatchExpression> matches = strings(parser).stream().map(value -> {
            IpCidr cidr = IpCidr.parse(value);
            return (MatchExpression) new IpCidrMatch(side, cidr.network(), cidr.prefixLength());
        }).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static void addPorts(JsonParser parser, List<MatchExpression> target, MatchSide side) throws IOException {
        List<MatchExpression> matches = integers(parser).stream()
                .map(port -> (MatchExpression) new PortMatch(side, port, port)).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static void addPortRanges(JsonParser parser, List<MatchExpression> target, MatchSide side) throws IOException {
        List<MatchExpression> matches = strings(parser).stream().map(value -> {
            int separator = value.indexOf(':');
            if (separator < 0) {
                throw new RuleProcessingException("Sing-box 端口范围非法: value=" + value);
            }
            return (MatchExpression) new PortMatch(side, Integer.parseInt(value.substring(0, separator)),
                    Integer.parseInt(value.substring(separator + 1)));
        }).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static void addNetworks(JsonParser parser, List<MatchExpression> target) throws IOException {
        List<MatchExpression> matches = strings(parser).stream().map(value -> (MatchExpression) new NetworkMatch(
                switch (value) {
                    case "tcp" -> NetworkMatch.Network.TCP;
                    case "udp" -> NetworkMatch.Network.UDP;
                    default -> throw new RuleProcessingException("Sing-box network 非法: value=" + value);
                })).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static void addProcesses(
            JsonParser parser,
            List<MatchExpression> target,
            ProcessMatch.ProcessField field) throws IOException {
        List<MatchExpression> matches = strings(parser).stream()
                .map(value -> (MatchExpression) new ProcessMatch(field, value)).toList();
        target.add(matches.size() == 1 ? matches.getFirst() : new AnyOf(matches));
    }

    private static List<String> strings(JsonParser parser) throws IOException {
        List<String> values = new ArrayList<>();
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            values.add(parser.getString());
            return List.copyOf(values);
        }
        require(parser.currentToken(), JsonToken.START_ARRAY, parser, "字段必须是字符串或字符串数组");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken(), JsonToken.VALUE_STRING, parser, "数组元素必须是字符串");
            values.add(parser.getString());
        }
        if (values.isEmpty()) {
            throw syntax(parser, "数组不得为空");
        }
        return List.copyOf(values);
    }

    private static List<Integer> integers(JsonParser parser) throws IOException {
        List<Integer> values = new ArrayList<>();
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            values.add(parser.getIntValue());
            return List.copyOf(values);
        }
        require(parser.currentToken(), JsonToken.START_ARRAY, parser, "字段必须是数字或数字数组");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw syntax(parser, "数组元素必须是整数");
            }
            values.add(parser.getIntValue());
        }
        if (values.isEmpty()) {
            throw syntax(parser, "数组不得为空");
        }
        return List.copyOf(values);
    }

    private static void require(JsonToken actual, JsonToken expected, JsonParser parser, String message) {
        if (actual != expected) {
            throw syntax(parser, message);
        }
    }

    private static RuleProcessingException syntax(JsonParser parser, String message) {
        return new RuleProcessingException(message + ": location=" + parser.currentLocation());
    }

    private enum DomainKind {
        EXACT,
        SUFFIX,
        KEYWORD,
        REGEX
    }
}

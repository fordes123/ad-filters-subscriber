package org.fordes.adfs.engine;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.RuleRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class SingBoxStreamingDecoder {

    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "domain",
            "domain_suffix",
            "domain_keyword",
            "domain_regex",
            "ip_cidr"
    );
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    private static final JsonStringEncoder STRING_ENCODER = JsonStringEncoder.getInstance();

    Result decode(
            InputStream input,
            Charset charset,
            BuildPlan.SourceSpec source,
            RuleConsumer consumer
    ) throws IOException {
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(charset, "charset 不能为空");
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(consumer, "consumer 不能为空");
        if (charset.equals(StandardCharsets.UTF_8)) {
            try (JsonParser parser = JSON_FACTORY.createParser(input)) {
                return decodeDocument(parser, source, consumer);
            }
        }
        Reader reader = new InputStreamReader(input, charset);
        try (JsonParser parser = JSON_FACTORY.createParser(reader)) {
            return decodeDocument(parser, source, consumer);
        }
    }

    private Result decodeDocument(
            JsonParser parser,
            BuildPlan.SourceSpec source,
            RuleConsumer consumer
    ) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            return Result.invalid("INVALID_SING_BOX_RULE_SET", "sing-box 规则集根节点必须是 object");
        }

        boolean versionSeen = false;
        boolean rulesSeen = false;
        long emitted = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                return Result.invalid("INVALID_SING_BOX_RULE_SET", "sing-box 根对象字段结构无效");
            }
            String field = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (field.equals("version")) {
                if (versionSeen) {
                    return Result.invalid("DUPLICATE_SING_BOX_VERSION", "sing-box version 字段不能重复");
                }
                versionSeen = true;
                boolean validVersion = valueToken == JsonToken.VALUE_NUMBER_INT
                        && parser.getBigIntegerValue().signum() > 0
                        && parser.getBigIntegerValue().bitLength() < Integer.SIZE;
                if (!validVersion) {
                    return Result.invalid("INVALID_SING_BOX_VERSION", "sing-box 规则集 version 必须是正整数");
                }
                continue;
            }
            if (field.equals("rules")) {
                if (rulesSeen) {
                    return Result.invalid("DUPLICATE_SING_BOX_RULES", "sing-box rules 字段不能重复");
                }
                rulesSeen = true;
                if (valueToken != JsonToken.START_ARRAY) {
                    return Result.invalid("INVALID_SING_BOX_RULES", "sing-box 规则集 rules 必须是 array");
                }
                Result rules = decodeRules(parser, source, consumer);
                if (rules.issue().isPresent()) {
                    return rules;
                }
                emitted += rules.emitted();
                continue;
            }
            parser.skipChildren();
        }

        if (!versionSeen) {
            return Result.invalid("INVALID_SING_BOX_VERSION", "sing-box 规则集 version 必须是正整数");
        }
        if (!rulesSeen) {
            return Result.invalid("INVALID_SING_BOX_RULES", "sing-box 规则集 rules 必须是 array");
        }
        if (parser.nextToken() != null) {
            return Result.invalid("TRAILING_SING_BOX_CONTENT", "sing-box 根对象后存在额外 JSON 内容");
        }
        return Result.success(emitted);
    }

    private Result decodeRules(
            JsonParser parser,
            BuildPlan.SourceSpec source,
            RuleConsumer consumer
    ) throws IOException {
        long emitted = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                return Result.invalid("INVALID_SING_BOX_RULE", "sing-box rules[] 必须是 object");
            }
            Result rule = decodeRule(parser, source, consumer);
            if (rule.issue().isPresent()) {
                return rule;
            }
            emitted += rule.emitted();
        }
        return Result.success(emitted);
    }

    private Result decodeRule(
            JsonParser parser,
            BuildPlan.SourceSpec source,
            RuleConsumer consumer
    ) throws IOException {
        JsonToken token = parser.nextToken();
        if (token != JsonToken.FIELD_NAME) {
            return Result.invalid(
                    "UNSUPPORTED_SING_BOX_RULE",
                    "sing-box 规则必须只包含一个受支持字段"
            );
        }
        String field = parser.currentName();
        if (!SUPPORTED_FIELDS.contains(field)) {
            return Result.invalid("UNSUPPORTED_SING_BOX_RULE", "sing-box 规则包含不受支持字段: " + field);
        }
        JsonToken valueToken = parser.nextToken();
        Result values = decodeValues(parser, valueToken, source, field, consumer);
        if (values.issue().isPresent()) {
            return values;
        }
        if (parser.nextToken() != JsonToken.END_OBJECT) {
            return Result.invalid(
                    "UNSUPPORTED_SING_BOX_RULE",
                    "sing-box 规则必须只包含一个受支持字段"
            );
        }
        return values;
    }

    private Result decodeValues(
            JsonParser parser,
            JsonToken token,
            BuildPlan.SourceSpec source,
            String field,
            RuleConsumer consumer
    ) throws IOException {
        if (token == JsonToken.VALUE_STRING) {
            return decodeValue(source, field, parser.getText(), consumer);
        }
        if (token != JsonToken.START_ARRAY) {
            return Result.invalid(
                    "INVALID_SING_BOX_RULE",
                    "sing-box " + field + " 必须是字符串或非空数组"
            );
        }

        long emitted = 0;
        boolean present = false;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            present = true;
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return Result.invalid("INVALID_SING_BOX_RULE", "sing-box " + field + " 包含无效值");
            }
            Result value = decodeValue(source, field, parser.getText(), consumer);
            if (value.issue().isPresent()) {
                return value;
            }
            emitted += value.emitted();
        }
        return present
                ? Result.success(emitted)
                : Result.invalid(
                        "INVALID_SING_BOX_RULE",
                        "sing-box " + field + " 必须是字符串或非空数组"
                );
    }

    private Result decodeValue(
            BuildPlan.SourceSpec source,
            String field,
            String value,
            RuleConsumer consumer
    ) throws IOException {
        if (value.isBlank()) {
            return Result.invalid("INVALID_SING_BOX_RULE", "sing-box " + field + " 包含无效值");
        }
        String raw = "{\"" + field + "\":\""
                + new String(STRING_ENCODER.quoteAsString(value)) + "\"}";
        RuleParser.ParseOutcome outcome = RuleParser.parseSingBoxValue(source, field, value, raw);
        if (outcome.issue().isPresent()) {
            RuleParser.ParseIssue issue = outcome.issue().orElseThrow();
            return Result.invalid(issue.code(), issue.message());
        }
        for (RuleRecord record : outcome.rules()) {
            consumer.accept(record);
        }
        return Result.success(outcome.rules().size());
    }

    @FunctionalInterface
    interface RuleConsumer {
        void accept(RuleRecord rule) throws IOException;
    }

    record Result(long emitted, Optional<RuleParser.ParseIssue> issue) {

        Result {
            Objects.requireNonNull(issue, "issue 不能为空");
            if (emitted < 0 || emitted > 0 && issue.isPresent()) {
                throw new IllegalArgumentException("sing-box decode result 状态无效");
            }
        }

        static Result success(long emitted) {
            return new Result(emitted, Optional.empty());
        }

        static Result invalid(String code, String message) {
            return new Result(0, Optional.of(new RuleParser.ParseIssue(code, message)));
        }
    }
}

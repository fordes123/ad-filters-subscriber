package org.fordes.adfs.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 项目唯一的 JSON 访问入口。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Json() {
    }

    public static JsonParser parser(String content) throws IOException {
        Objects.requireNonNull(content, "content 不能为空");
        return FACTORY.createParser(content);
    }

    public static JsonParser parser(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input 不能为空");
        return FACTORY.createParser(input);
    }

    public static JsonParser parser(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader 不能为空");
        return FACTORY.createParser(reader);
    }

    public static JsonGenerator generator(Writer writer) throws IOException {
        Objects.requireNonNull(writer, "writer 不能为空");
        return FACTORY.createGenerator(writer);
    }

    public static <T> T readYaml(Path path, Class<T> type) throws IOException {
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        return YAML.readerFor(type)
                .with(DeserializationFeature.UNWRAP_ROOT_VALUE)
                .readValue(path.toFile());
    }

    public static String singleValueRule(String field, String value) {
        Objects.requireNonNull(field, "field 不能为空");
        Objects.requireNonNull(value, "value 不能为空");
        ObjectNode rule = MAPPER.createObjectNode();
        rule.set(field, MAPPER.valueToTree(List.of(value)));
        try {
            return MAPPER.writeValueAsString(rule);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化 sing-box 规则", error);
        }
    }
}

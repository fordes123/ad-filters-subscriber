package org.fordes.adfs.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ConfigLoader {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    public BuildPlan load(Path configPath) throws IOException {
        return load(configPath, Optional.empty());
    }

    public BuildPlan load(Path configPath, Optional<Path> outputDirectoryOverride)
            throws IOException {
        Objects.requireNonNull(
                outputDirectoryOverride,
                "outputDirectoryOverride 不能为空"
        );
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("配置文件不存在或不是普通文件: " + configPath);
        }
        JsonNode root = YAML.readTree(configPath.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("配置文件根节点必须是 mapping");
        }
        rejectUnknownFields(root, "配置文件根节点", Set.of("application"));
        return loadApplication(requiredObject(root, "application"), outputDirectoryOverride);
    }

    private static BuildPlan loadApplication(
            JsonNode application,
            Optional<Path> outputDirectoryOverride
    ) {
        rejectUnknownFields(
                application,
                "application",
                Set.of("input", "output", "source-loading", "processing", "logging")
        );

        List<BuildPlan.SourceSpec> sources = new ArrayList<>();
        for (JsonNode node : requiredArray(application, "input")) {
            rejectUnknownFields(
                    node,
                    "application.input[]",
                    Set.of("name", "path", "type", "dialect", "priority")
            );
            RuleFormat format = RuleFormat.parse(text(node, "type", "easylist"));
            sources.add(new BuildPlan.SourceSpec(
                    requiredText(node, "name"),
                    requiredText(node, "path"),
                    format,
                    adblockDialect(node, format),
                    clashDialect(node, format),
                    integer(node, "priority", 0)
            ));
        }

        JsonNode output = requiredObject(application, "output");
        rejectUnknownFields(output, "application.output", Set.of("path", "file_header", "files"));
        Path configuredOutputDirectory = Path.of(text(output, "path", "rule"));
        Path outputDirectory = outputDirectoryOverride.orElse(configuredOutputDirectory);
        String globalHeader = text(output, "file_header", "");
        List<BuildPlan.OutputSpec> outputs = new ArrayList<>();
        for (JsonNode node : requiredArray(output, "files")) {
            rejectUnknownFields(
                    node,
                    "application.output.files[]",
                    Set.of("name", "type", "dialect", "desc", "file_header", "rule")
            );
            RuleFormat format = RuleFormat.parse(requiredText(node, "type"));
            String itemHeader = text(node, "file_header", globalHeader);
            if (itemHeader.isBlank()) {
                itemHeader = globalHeader;
            }
            outputs.add(new BuildPlan.OutputSpec(
                    outputDirectory.resolve(requiredText(node, "name")),
                    format,
                    adblockDialect(node, format),
                    clashDialect(node, format),
                    text(node, "desc", ""),
                    itemHeader,
                    strings(node.path("rule"))
            ));
        }

        JsonNode sourceLoading = optionalObject(application, "source-loading");
        JsonNode processing = optionalObject(application, "processing");
        JsonNode logging = optionalObject(application, "logging");
        rejectUnknownFields(
                processing,
                "application.processing",
                Set.of(
                        "min-rule-length",
                        "max-rule-length",
                        "excluded-domains",
                        "allow-narrowing",
                        "allow-broadening",
                        "dns-validation"
                )
        );
        JsonNode dnsValidation = optionalObject(processing, "dns-validation");
        rejectUnknownFields(
                logging,
                "application.logging",
                Set.of("level")
        );
        return new BuildPlan(
                sources,
                outputs,
                sourceLoadingPolicy(sourceLoading),
                processingPolicy(processing, dnsValidation),
                loggingPolicy(logging)
        );
    }

    private static BuildPlan.LoggingPolicy loggingPolicy(JsonNode node) {
        return new BuildPlan.LoggingPolicy(logLevel(node));
    }

    private static BuildPlan.LogLevel logLevel(JsonNode node) {
        String value = text(node, "level", BuildPlan.LogLevel.INFO.name);
        String name = value.trim();
        for (BuildPlan.LogLevel level : BuildPlan.LogLevel.values()) {
            if (level.name.equalsIgnoreCase(name)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知日志等级: " + value);
    }

    private static BuildPlan.ProcessingPolicy processingPolicy(
            JsonNode node,
            JsonNode dnsValidation
    ) {
        return new BuildPlan.ProcessingPolicy(
                integer(node, "min-rule-length", 0),
                integer(node, "max-rule-length", 0),
                strings(node.path("excluded-domains")),
                bool(node, "allow-narrowing", true),
                bool(node, "allow-broadening", false),
                dnsValidationPolicy(dnsValidation)
        );
    }

    private static BuildPlan.SourceLoadingPolicy sourceLoadingPolicy(JsonNode node) {
        rejectUnknownFields(
                node,
                "application.source-loading",
                Set.of(
                        "local-charset",
                        "http-charset",
                        "buffer-size",
                        "connect-timeout",
                        "request-timeout"
                )
        );
        Charset localCharset = Charset.forName(
                text(node, "local-charset", StandardCharsets.UTF_8.name()));
        Charset httpCharset = Charset.forName(
                text(node, "http-charset", StandardCharsets.UTF_8.name()));
        Duration connectTimeout = duration(text(node, "connect-timeout", "10s"));
        Duration requestTimeout = duration(text(node, "request-timeout", "30s"));
        int bufferSize = dataSize(text(node, "buffer-size", "4KB"));
        return new BuildPlan.SourceLoadingPolicy(
                localCharset,
                httpCharset,
                connectTimeout,
                requestTimeout,
                bufferSize,
                bufferSize
        );
    }

    private static BuildPlan.DnsValidationPolicy dnsValidationPolicy(JsonNode node) {
        rejectUnknownFields(
                node,
                "application.processing.dns-validation",
                Set.of("enabled", "server", "port", "timeout", "concurrency")
        );
        boolean enabled = bool(node, "enabled", false);
        Duration timeout = duration(text(node, "timeout", "1s"));
        int concurrency = integer(node, "concurrency", 128);
        JsonNode serverNode = node.path("server");
        if (serverNode.isMissingNode() || serverNode.isNull()) {
            if (!node.path("port").isMissingNode()) {
                throw new IllegalArgumentException(
                        "application.processing.dns-validation.port 需要同时配置 server");
            }
            return new BuildPlan.DnsValidationPolicy(enabled, timeout, concurrency, Optional.empty());
        }
        BuildPlan.DnsServer server = new BuildPlan.DnsServer(
                requiredText(node, "server"),
                integer(node, "port", 53)
        );
        return new BuildPlan.DnsValidationPolicy(enabled, timeout, concurrency, Optional.of(server));
    }

    private static Set<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("配置字段必须是 sequence: " + node);
        }
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("sequence 中的值必须是非空字符串: " + value);
            }
            values.add(value.textValue());
        }
        return Set.copyOf(values);
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " 必须是 mapping");
        }
        return value;
    }

    private static JsonNode optionalObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return value;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " 必须是 mapping");
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(field + " 必须是非空 sequence");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " 必须是 boolean");
        }
        return value.booleanValue();
    }

    private static DialectProfile dialect(
            JsonNode node,
            String field,
            DialectProfile fallback
    ) {
        String value = text(node, field, fallback.name);
        String name = value.trim();
        for (DialectProfile profile : DialectProfile.values()) {
            if (profile.name.equalsIgnoreCase(name)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("未知 Adblock 方言: " + value);
    }

    private static DialectProfile adblockDialect(JsonNode node, RuleFormat format) {
        return format == RuleFormat.CLASH
                ? DialectProfile.ADBLOCK_BASE
                : dialect(node, "dialect", defaultDialect(format));
    }

    private static ClashDialect clashDialect(JsonNode node, RuleFormat format) {
        return format == RuleFormat.CLASH
                ? ClashDialect.parse(text(node, "dialect", ClashDialect.CLASSICAL.name))
                : ClashDialect.CLASSICAL;
    }

    private static DialectProfile defaultDialect(RuleFormat format) {
        return switch (format) {
            case EASYLIST -> DialectProfile.ABP;
            case DNS -> DialectProfile.ADGUARD;
            case HOSTS, DNSMASQ, SMARTDNS, CLASH, SING_BOX -> DialectProfile.ADBLOCK_BASE;
        };
    }

    private static Duration duration(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.startsWith("p")) {
                return Duration.parse(value.trim().toUpperCase(Locale.ROOT));
            }
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            return Duration.parse(value.trim().toUpperCase(Locale.ROOT));
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException error) {
            throw new IllegalArgumentException("无效 duration: " + value, error);
        }
    }

    private static int dataSize(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        long multiplier;
        String number;
        if (normalized.endsWith("KB")) {
            multiplier = 1024L;
            number = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            number = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("B")) {
            multiplier = 1L;
            number = normalized.substring(0, normalized.length() - 1);
        } else {
            multiplier = 1L;
            number = normalized;
        }
        try {
            return Math.toIntExact(Math.multiplyExact(Long.parseLong(number.trim()), multiplier));
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("无效 data size: " + value, error);
        }
    }

    private static void rejectUnknownFields(
            JsonNode node,
            String location,
            Set<String> allowed
    ) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(location + " 必须是 mapping");
        }
        for (String field : node.propertyStream().map(java.util.Map.Entry::getKey).toList()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(location + " 包含未知字段: " + field);
            }
        }
    }
}

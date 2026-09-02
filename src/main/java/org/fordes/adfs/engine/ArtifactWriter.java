package org.fordes.adfs.engine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.json.Json;
import org.fordes.adfs.syntax.RuleFormat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 负责产物外壳和规则流写入；规则本身的格式转换由 {@link RuleEncoder} 完成。
 */
final class ArtifactWriter {

    private final BuildPlan.OutputSpec output;

    ArtifactWriter(BuildPlan.OutputSpec output) {
        this.output = Objects.requireNonNull(output, "output 不能为空");
    }

    void write(Path target, SpillableRuleDeduplicator.Result rules, String header) throws IOException {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(rules, "rules 不能为空");
        Objects.requireNonNull(header, "header 不能为空");
        if (output.format() == RuleFormat.SING_BOX) {
            writeSingBox(target, rules);
            return;
        }
        writeText(target, rules, header);
    }

    private void writeText(
            Path target,
            SpillableRuleDeduplicator.Result rules,
            String header
    ) throws IOException {
        try (BufferedWriter writer = openWriter(target)) {
            writeHeader(writer, header);
            rules.forEach(candidate -> {
                writer.write(candidate.content());
                writer.newLine();
            });
        }
    }

    private void writeHeader(BufferedWriter writer, String header) throws IOException {
        if (!header.isBlank()) {
            String prefix = headerPrefix(output.format());
            for (String headerLine : header.lines().toList()) {
                if (!headerLine.isBlank()) {
                    writer.write(prefix);
                    writer.write(headerLine.strip());
                    writer.newLine();
                }
            }
        }
        if (output.format() == RuleFormat.CLASH) {
            writer.write("payload:");
            writer.newLine();
        }
    }

    private void writeSingBox(Path target, SpillableRuleDeduplicator.Result rules) throws IOException {
        try (BufferedWriter writer = openWriter(target);
             JsonGenerator output = Json.generator(writer).useDefaultPrettyPrinter()) {
            output.writeStartObject();
            output.writeNumberField("version", 2);
            output.writeArrayFieldStart("rules");
            rules.forEach(candidate -> writeJsonRule(output, candidate));
            output.writeEndArray();
            output.writeEndObject();
            output.writeRaw(System.lineSeparator());
        }
    }

    private void writeJsonRule(
            JsonGenerator output,
            SpillableRuleDeduplicator.Candidate candidate
    ) throws IOException {
        try (JsonParser rule = Json.parser(candidate.content())) {
            if (rule.nextToken() == null) {
                throw new IOException("sing-box 规则 JSON 为空");
            }
            output.copyCurrentStructure(rule);
            if (rule.nextToken() != null) {
                throw new IOException("sing-box 规则 JSON 包含多个根节点");
            }
        }
    }

    private static String headerPrefix(RuleFormat format) {
        return switch (format) {
            case EASYLIST, DNS -> "! ";
            case HOSTS, DNSMASQ, SMARTDNS, CLASH -> "# ";
            case SING_BOX -> "";
        };
    }

    private static BufferedWriter openWriter(Path path) throws IOException {
        return Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }
}

package dev.fordes.adfs.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.fordes.adfs.config.EffectiveConfig;
import dev.fordes.adfs.config.OutputSpec;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.format.RuleParser;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.source.LocalSourceReader;
import dev.fordes.adfs.source.SourceSession;

public final class ParserTestSupport {

    private ParserTestSupport() {
    }

    public static List<RuleEntry> parse(
            Path source,
            byte[] content,
            RuleParser parser,
            RuleType type,
            RuleDialect dialect) throws IOException {
        Files.write(source, content);
        Path outputDir = source.getParent().resolve("output");
        OutputSpec output = TestConfigs.textOutput("result.txt", RuleType.HOSTS, RuleDialect.NONE);
        EffectiveConfig config = TestConfigs.create(source, type, dialect, outputDir, List.of(output), false);
        List<RuleEntry> entries = new ArrayList<>();
        try (SourceSession session = new LocalSourceReader().open(config.inputs().getFirst(), config)) {
            parser.parse(session, entries::add);
        }
        return List.copyOf(entries);
    }

    public static List<RuleEntry> parse(
            Path source,
            String content,
            RuleParser parser,
            RuleType type,
            RuleDialect dialect) throws IOException {
        return parse(source, content.getBytes(StandardCharsets.UTF_8), parser, type, dialect);
    }
}

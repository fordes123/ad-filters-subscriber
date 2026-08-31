package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoragePipelineTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void roundTripsCanonicalAndExtendedRuleSegments() throws Exception {
        BuildPlan.SourceSpec source = source();
        Path segment = tempDirectory.resolve("rules.segment");
        RuleRecord canonical = record("||example.com^", "example.com");
        RuleRecord extended = new RuleRecord(
                source.id(),
                source.format(),
                source.dialect(),
                source.clashDialect(),
                "example.com##+js(json-prune, ad)",
                Optional.empty(),
                Optional.of(new AdblockExtendedRule(
                        AdblockExtendedRule.Syntax.UBO_SCRIPTLET,
                        AdblockExtendedRule.Action.APPLY,
                        Optional.empty(),
                        "example.com",
                        "+js(json-prune, ad)",
                        Optional.of("json-prune")
                )),
                RuleRecord.SourceSyntax.UBO_SCRIPTLET
        );

        try (RuleSegment.Writer writer = RuleSegment.writer(segment, source)) {
            writer.write(canonical);
            writer.write(extended);
        }
        try (RuleSegment.Reader reader = RuleSegment.reader(segment)) {
            assertEquals(canonical, reader.read());
            assertEquals(extended, reader.read());
            assertNull(reader.read());
            assertNull(reader.read());
        }
    }

    @Test
    void rejectsMismatchedAndCorruptRuleSegments() throws Exception {
        Path segment = tempDirectory.resolve("mismatch.segment");
        try (RuleSegment.Writer writer = RuleSegment.writer(segment, source())) {
            RuleRecord mismatched = new RuleRecord(
                    "other",
                    RuleFormat.EASYLIST,
                    DialectProfile.ABP,
                    ClashDialect.CLASSICAL,
                    "||example.com^",
                    Optional.empty(),
                    RuleRecord.SourceSyntax.NETWORK
            );
            IOException error = assertThrows(IOException.class, () -> writer.write(mismatched));
            assertTrue(error.getMessage().contains("来源元数据不一致"));
        }

        Path corrupt = tempDirectory.resolve("corrupt.segment");
        Files.write(corrupt, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        assertThrows(IOException.class, () -> RuleSegment.reader(corrupt));
    }

    @Test
    void spillsDeduplicationToDiskAndPreservesFirstOccurrenceOrder() throws Exception {
        int uniqueRules = 5_000;
        String padding = "x".repeat(160);
        try (BuildWorkspace workspace = BuildWorkspace.create();
             SpillableRuleDeduplicator deduplicator =
                     new SpillableRuleDeduplicator(workspace, 1024L * 1024L)) {
            for (int index = 0; index < uniqueRules; index++) {
                String content = "||%05d.%s.example.com^".formatted(index, padding);
                deduplicator.add(index, content, record(content, "%05d.example.com".formatted(index)));
                deduplicator.add(uniqueRules + index, content,
                        record(content, "%05d.example.com".formatted(index)));
            }

            SpillableRuleDeduplicator.Result result = deduplicator.finish();
            List<Long> sequences = new ArrayList<>();
            result.forEach(candidate -> sequences.add(candidate.sequence()));

            assertEquals(uniqueRules, result.unique());
            assertEquals(uniqueRules, sequences.size());
            assertEquals(0L, sequences.getFirst());
            assertEquals(uniqueRules - 1L, sequences.getLast());
            for (int index = 1; index < sequences.size(); index++) {
                assertTrue(sequences.get(index - 1) < sequences.get(index));
            }
        }
    }

    @Test
    void externallySortsOneLargeDnsReferenceStream() throws Exception {
        int references = 20_000;
        try (BuildWorkspace workspace = BuildWorkspace.create()) {
            DnsReferenceStore store = new DnsReferenceStore(workspace);
            try (store) {
                assertTrue(store.path().isEmpty());
                for (int index = references - 1; index >= 0; index--) {
                    store.write(new DnsReferenceStore.Reference(
                            "domain%05d.test-domain.com".formatted(index),
                            index % 3,
                            index
                    ));
                }
            }

            Path sorted = DnsReferenceStore.sort(
                    store.path().orElseThrow(),
                    workspace,
                    1024L * 1024L
            );
            int count = 0;
            String previous = null;
            try (DnsReferenceStore.Reader reader = DnsReferenceStore.reader(sorted)) {
                DnsReferenceStore.Reference reference;
                while ((reference = reader.read()) != null) {
                    if (previous != null) {
                        assertTrue(previous.compareTo(reference.domain()) < 0);
                    }
                    previous = reference.domain();
                    count++;
                }
            }

            assertEquals(references, count);
            assertEquals("domain19999.test-domain.com", previous);
        }
    }

    private static RuleRecord record(String raw, String domain) {
        BuildPlan.SourceSpec source = source();
        return new RuleRecord(
                source.id(),
                source.format(),
                source.dialect(),
                source.clashDialect(),
                raw,
                Optional.of(new CanonicalRule(
                        CanonicalRule.MatchType.DOMAIN_SUFFIX,
                        domain,
                        CanonicalRule.Action.BLOCK,
                        Optional.empty(),
                        0
                )),
                RuleRecord.SourceSyntax.NETWORK
        );
    }

    private static BuildPlan.SourceSpec source() {
        return new BuildPlan.SourceSpec(
                "source",
                "unused",
                RuleFormat.EASYLIST,
                DialectProfile.ABP,
                ClashDialect.CLASSICAL,
                0
        );
    }
}

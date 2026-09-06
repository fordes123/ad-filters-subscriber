package dev.fordes.adfs.validation.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fordes.adfs.config.EffectiveConfig.DnsCacheConfig;
import dev.fordes.adfs.config.EffectiveConfig.DnsConfig;
import dev.fordes.adfs.config.RuleDialect;
import dev.fordes.adfs.config.RuleType;
import dev.fordes.adfs.report.ProcessingMetrics;
import dev.fordes.adfs.rule.dedup.CanonicalStore;
import dev.fordes.adfs.rule.dedup.RuleDeduplicator;
import dev.fordes.adfs.rule.model.DomainEnvelope;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RuleAction;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.spool.RuleSpool;

final class DnsValidatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesOrderMergesInflightQueriesAndDropsInvalidDomains() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        ControlledResolver resolver = new ControlledResolver(release);
        DnsValidator validator = new DnsValidator(_ -> resolver);
        DnsConfig config = new DnsConfig(true, List.of(), 4, Duration.ofSeconds(1), 0, 4,
                new DnsCacheConfig(1_024, Duration.ofMinutes(1), Duration.ofSeconds(30)));
        ProcessingMetrics metrics = new ProcessingMetrics(1);
        List<RuleEntry> accepted = new ArrayList<>();
        Path spoolPath = temporaryDirectory.resolve("rules.bin");
        Path canonicalPath = temporaryDirectory.resolve("canonical.bin");
        Thread releaser = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(100);
                release.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        try (RuleSpool spool = new RuleSpool(spoolPath, 1_024);
                CanonicalStore store = new CanonicalStore(canonicalPath)) {
            spool.accept(new DomainRule(new ExactDomain(new DomainName("valid.example")), RuleAction.BLOCK));
            spool.accept(new DomainRule(new SuffixDomain(new DomainName("valid.example")), RuleAction.BLOCK));
            spool.accept(new DomainRule(new ExactDomain(new DomainName("invalid.example")), RuleAction.BLOCK));
            spool.accept(new OpaqueRule(RuleType.ADBLOCK, RuleDialect.ADGUARD,
                    DomainEnvelope.UNKNOWN, "/opaque/"));
            validator.validate(spool, config, new RuleDeduplicator(store), _ -> true, accepted::add, metrics);
        }
        releaser.join();

        assertEquals(3, accepted.size());
        assertEquals("valid.example", ((DomainRule) accepted.get(0)).pattern().value());
        assertEquals("valid.example", ((DomainRule) accepted.get(1)).pattern().value());
        assertTrue(accepted.get(2) instanceof OpaqueRule);
        assertEquals(3, metrics.snapshot().dns().checked());
        assertEquals(2, metrics.snapshot().dns().valid());
        assertEquals(1, metrics.snapshot().dns().invalid());
        assertEquals(1, metrics.snapshot().dns().skipped());
        assertEquals(1, metrics.snapshot().dns().merged());
        assertEquals(2, resolver.calls());
    }

    private static final class ControlledResolver implements DnsResolver {

        private final CountDownLatch release;
        private int calls;

        private ControlledResolver(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public synchronized DnsResult resolve(DomainName domain) {
            calls++;
            if (domain.value().equals("valid.example")) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待测试解析器释放超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试解析器被中断", exception);
                }
                return DnsResult.VALID;
            }
            return DnsResult.INVALID;
        }

        @Override
        public int cacheSize() {
            return 2;
        }

        @Override
        public long retries() {
            return 0;
        }

        private synchronized int calls() {
            return calls;
        }
    }
}

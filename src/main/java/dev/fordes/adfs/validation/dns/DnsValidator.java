package dev.fordes.adfs.validation.dns;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

import jakarta.inject.Singleton;

import dev.fordes.adfs.config.EffectiveConfig.DnsConfig;
import dev.fordes.adfs.error.DnsException;
import dev.fordes.adfs.format.RuleConsumer;
import dev.fordes.adfs.report.ProcessingMetrics;
import dev.fordes.adfs.rule.model.DomainName;
import dev.fordes.adfs.rule.model.AdblockNetworkRule;
import dev.fordes.adfs.rule.model.AdblockPattern;
import dev.fordes.adfs.rule.model.CosmeticRule;
import dev.fordes.adfs.rule.model.DomainRule;
import dev.fordes.adfs.rule.model.ExactDomain;
import dev.fordes.adfs.rule.model.HostMappingRule;
import dev.fordes.adfs.rule.model.IpCidrRule;
import dev.fordes.adfs.rule.model.OpaqueRule;
import dev.fordes.adfs.rule.model.RuleEntry;
import dev.fordes.adfs.rule.model.RouteRule;
import dev.fordes.adfs.rule.model.SuffixDomain;
import dev.fordes.adfs.rule.dedup.RuleDeduplicator;
import dev.fordes.adfs.rule.spool.RuleSpool;

@Singleton
@Slf4j
public final class DnsValidator {

    private final Function<DnsConfig, DnsResolver> resolverFactory;

    public DnsValidator() {
        this(DnsjavaResolver::new);
    }

    DnsValidator(Function<DnsConfig, DnsResolver> resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    public void validate(
            RuleSpool spool,
            DnsConfig config,
            RuleDeduplicator deduplicator,
            Predicate<RuleEntry> filter,
            RuleConsumer consumer,
            ProcessingMetrics metrics) {
        DnsResolver resolver = resolverFactory.apply(config);
        Semaphore permits = new Semaphore(config.concurrency(), true);
        ConcurrentHashMap<DomainName, CompletableFuture<DnsResult>> inFlight = new ConcurrentHashMap<>();
        Queue<PendingRule> window = new ArrayDeque<>(config.concurrency());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            spool.replay(entry -> {
                if (!filter.test(entry)) {
                    return;
                }
                if (!deduplicator.add(entry)) {
                    metrics.duplicate();
                    return;
                }
                metrics.unique();
                if (window.size() == config.concurrency()) {
                    flushFirst(window, consumer, metrics);
                }
                Optional<DomainName> domain = domain(entry);
                if (domain.isEmpty()) {
                    metrics.dnsSkipped();
                    window.add(new PendingRule(entry, CompletableFuture.completedFuture(DnsResult.SKIPPED),
                            log.isDebugEnabled() ? MDC.getCopyOfContextMap() : Map.of()));
                    return;
                }
                metrics.dnsChecked();
                window.add(new PendingRule(entry,
                        resolve(domain.orElseThrow(), resolver, executor, permits, inFlight, metrics),
                        log.isDebugEnabled() ? MDC.getCopyOfContextMap() : Map.of()));
            });
            while (!window.isEmpty()) {
                flushFirst(window, consumer, metrics);
            }
        }
        metrics.finishDns(resolver.retries(), resolver.cacheSize());
    }

    private static CompletableFuture<DnsResult> resolve(
            DomainName domain,
            DnsResolver resolver,
            ExecutorService executor,
            Semaphore permits,
            ConcurrentHashMap<DomainName, CompletableFuture<DnsResult>> inFlight,
            ProcessingMetrics metrics) {
        CompletableFuture<DnsResult> promise = new CompletableFuture<>();
        CompletableFuture<DnsResult> existing = inFlight.putIfAbsent(domain, promise);
        if (existing != null) {
            metrics.dnsMerged();
            return existing;
        }
        executor.submit(() -> {
            boolean acquired = false;
            try {
                permits.acquire();
                acquired = true;
                promise.complete(resolver.resolve(domain));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                promise.completeExceptionally(new DnsException(
                        "DNS 查询等待并发许可时被中断: domain=" + domain.value(), exception));
            } catch (RuntimeException exception) {
                promise.completeExceptionally(exception);
            } finally {
                if (acquired) {
                    permits.release();
                }
                inFlight.remove(domain, promise);
            }
        });
        return promise;
    }

    private static Optional<DomainName> domain(RuleEntry entry) {
        return switch (entry) {
            case DomainRule(var pattern, var action) -> switch (pattern) {
                case ExactDomain(DomainName domain) -> Optional.of(domain);
                case SuffixDomain(DomainName domain) -> Optional.of(domain);
                default -> Optional.empty();
            };
            case AdblockNetworkRule rule -> rule.pattern().kind() == AdblockPattern.Kind.DOMAIN_ANCHOR
                    ? Optional.of(new DomainName(rule.pattern().value())) : Optional.empty();
            case CosmeticRule _, HostMappingRule _, IpCidrRule _, RouteRule _, OpaqueRule _ -> Optional.empty();
        };
    }

    private static void flushFirst(Queue<PendingRule> window, RuleConsumer consumer, ProcessingMetrics metrics) {
        PendingRule pending = window.remove();
        DnsResult result;
        try {
            result = pending.result().join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof DnsException dnsException) {
                throw dnsException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DnsException("DNS 验证任务异常", cause);
        }
        boolean logContext = log.isDebugEnabled();
        Map<String, String> previousContext = logContext ? MDC.getCopyOfContextMap() : null;
        if (logContext) {
            MDC.setContextMap(pending.logContext());
        }
        try {
            switch (result) {
                case VALID -> {
                    metrics.dnsValid();
                    consumer.accept(pending.entry());
                }
                case INVALID -> metrics.dnsInvalid();
                case SKIPPED -> consumer.accept(pending.entry());
            }
        } finally {
            if (logContext) {
                if (previousContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContext);
                }
            }
        }
    }
}

record PendingRule(RuleEntry entry, CompletableFuture<DnsResult> result, Map<String, String> logContext) {
}

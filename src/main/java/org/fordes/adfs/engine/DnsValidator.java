package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;
import org.xbill.DNS.lookup.LookupResult;
import org.xbill.DNS.lookup.LookupSession;
import org.xbill.DNS.lookup.NoSuchDomainException;
import org.xbill.DNS.lookup.NoSuchRRSetException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 dnsjava 原生缓存和异步解析器校验域名。
 */
final class DnsValidator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsValidator.class);

    private static final int MAX_CACHE_TTL_SECONDS = 300;
    private static final int MAX_NEGATIVE_CACHE_TTL_SECONDS = 60;
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final int TIMEOUT_BREAKER_THRESHOLD = 3;
    private static final Duration TIMEOUT_BREAKER_DURATION = Duration.ofSeconds(30);

    private final ExecutorService executor;
    private final Semaphore permits;
    private final Cache cache;
    private final List<ResolverSlot> resolvers;
    private final Object loadBalancerLock = new Object();

    DnsValidator(BuildPlan.DnsValidationPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy 不能为空");
        if (policy.servers().isEmpty()) {
            throw new IllegalArgumentException("DNS 校验启用时必须配置 servers");
        }
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("adfs-dns-", 0).factory());
        permits = new Semaphore(policy.concurrency());
        cache = new Cache(DClass.IN);
        cache.setMaxCache(MAX_CACHE_TTL_SECONDS);
        cache.setMaxNCache(MAX_NEGATIVE_CACHE_TTL_SECONDS);
        cache.setMaxEntries(MAX_CACHE_ENTRIES);
        try {
            resolvers = createResolvers(policy.servers(), policy.timeout());
        } catch (IOException | RuntimeException error) {
            executor.close();
            throw error;
        }
    }

    Status resolve(String domain) throws IOException, InterruptedException {
        Objects.requireNonNull(domain, "domain 不能为空");
        return resolveDomain(domain);
    }

    Future<Status> resolveAsync(String domain) {
        Objects.requireNonNull(domain, "domain 不能为空");
        return executor.submit(() -> resolveDomain(domain));
    }

    @Override
    public void close() {
        executor.close();
    }

    private List<ResolverSlot> createResolvers(List<String> endpoints, Duration timeout)
            throws IOException {
        List<ResolverSlot> result = new ArrayList<>(endpoints.size());
        try {
            for (String endpoint : endpoints) {
                InetSocketAddress address = parseEndpoint(endpoint);
                SimpleResolver resolver = new SimpleResolver(address);
                resolver.setTimeout(timeout);
                LookupSession session = LookupSession.builder()
                        .resolver(resolver)
                        .cache(cache)
                        .executor(executor)
                        .clearSearchPath()
                        .build();
                result.add(new ResolverSlot(endpoint, session));
            }
        } catch (IOException | RuntimeException error) {
            result.clear();
            throw error;
        }
        return List.copyOf(result);
    }

    private Status resolveDomain(String domain) throws IOException, InterruptedException {
        Name name;
        try {
            name = Name.fromString(domain, Name.root);
        } catch (IllegalArgumentException error) {
            throw new IOException("DNS 域名无效: domain=" + domain, error);
        }

        permits.acquire();
        try {
            Set<ResolverSlot> attempted = new HashSet<>();
            IOException lastError = null;
            for (int attempt = 1; attempt <= resolvers.size(); attempt++) {
                ResolverSlot resolver = selectResolver(attempted);
                if (resolver == null) {
                    break;
                }
                attempted.add(resolver);
                try {
                    Status status = resolveWith(resolver, name, domain);
                    if (markSuccess(resolver)) {
                        LOGGER.info("DNS 解析器恢复: server={}", resolver.endpoint());
                    }
                    return status;
                } catch (IOException error) {
                    lastError = error;
                    if (isTimeout(error)) {
                        if (markTimeout(resolver)) {
                            LOGGER.warn(
                                    "DNS 解析器熔断: server={}, timeoutStreak={}, threshold={}, duration={}",
                                    resolver.endpoint(),
                                    TIMEOUT_BREAKER_THRESHOLD,
                                    TIMEOUT_BREAKER_THRESHOLD,
                                    TIMEOUT_BREAKER_DURATION
                            );
                        }
                    } else {
                        markFailure(resolver);
                    }
                    if (attempt < resolvers.size()) {
                        LOGGER.debug(
                                "DNS 查询失败，准备切换解析器: domain={}, server={}, attempt={}, maxAttempts={}, reason={}",
                                domain,
                                resolver.endpoint(),
                                attempt,
                                resolvers.size(),
                                error.getMessage()
                        );
                    }
                }
            }
            if (lastError != null) {
                LOGGER.debug(
                        "DNS 查询失败，保留对应规则: domain={}, attempts={}, reason={}",
                        domain,
                        attempted.size(),
                        lastError.getMessage()
                );
            }
            return Status.UNKNOWN;
        } finally {
            permits.release();
        }
    }

    private Status resolveWith(ResolverSlot resolver, Name name, String domain)
            throws IOException, InterruptedException {
        if (hasAddressRecord(resolver, name, Type.A, domain)) {
            return Status.EXISTS;
        }
        return hasAddressRecord(resolver, name, Type.AAAA, domain)
                ? Status.EXISTS
                : Status.INVALID;
    }

    private boolean hasAddressRecord(
            ResolverSlot resolver,
            Name name,
            int type,
            String domain
    ) throws IOException, InterruptedException {
        CompletableFuture<LookupResult> future;
        try {
            future = resolver.session()
                    .lookupAsync(name, type, DClass.IN)
                    .toCompletableFuture();
        } catch (RuntimeException error) {
            throw queryFailure(domain, resolver, error);
        }
        try {
            LookupResult result = future.get();
            return result.getRecords().stream().anyMatch(record -> type == Type.A
                    ? record instanceof ARecord
                    : record instanceof AAAARecord);
        } catch (InterruptedException error) {
            future.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof NoSuchDomainException || cause instanceof NoSuchRRSetException) {
                return false;
            }
            throw queryFailure(domain, resolver, cause);
        }
    }

    private static IOException queryFailure(
            String domain,
            ResolverSlot resolver,
            Throwable cause
    ) {
        return new IOException(
                "DNS 查询失败: domain=" + domain + ", server=" + resolver.endpoint(),
                cause
        );
    }

    private ResolverSlot selectResolver(Set<ResolverSlot> attempted) {
        synchronized (loadBalancerLock) {
            long now = System.nanoTime();
            ResolverSlot selected = null;
            int available = 0;
            for (ResolverSlot resolver : resolvers) {
                if (attempted.contains(resolver) || !resolver.available(now)) {
                    continue;
                }
                resolver.addCurrentWeight();
                available++;
                if (selected == null || resolver.currentWeight() > selected.currentWeight()) {
                    selected = resolver;
                }
            }
            if (selected == null) {
                return null;
            }
            selected.subtractCurrentWeight(available);
            selected.beginProbe();
            return selected;
        }
    }

    private boolean markSuccess(ResolverSlot resolver) {
        synchronized (loadBalancerLock) {
            boolean recovered = resolver.isOpen();
            resolver.markSuccess();
            return recovered;
        }
    }

    private boolean markTimeout(ResolverSlot resolver) {
        synchronized (loadBalancerLock) {
            boolean wasHalfOpen = resolver.isHalfOpen();
            resolver.markTimeout(System.nanoTime(), wasHalfOpen);
            return resolver.isOpen();
        }
    }

    private void markFailure(ResolverSlot resolver) {
        synchronized (loadBalancerLock) {
            resolver.markFailure();
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException || current instanceof CompletionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private static InetSocketAddress parseEndpoint(String endpoint) throws IOException {
        String value = endpoint.trim();
        String host;
        String portText;
        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket <= 1
                    || closingBracket + 1 >= value.length()
                    || value.charAt(closingBracket + 1) != ':') {
                throw invalidEndpoint(endpoint);
            }
            host = value.substring(1, closingBracket);
            portText = value.substring(closingBracket + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator <= 0
                    || separator != value.indexOf(':')
                    || separator == value.length() - 1) {
                throw invalidEndpoint(endpoint);
            }
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            throw invalidEndpoint(endpoint, error);
        }
        if (port < 1 || port > 65_535) {
            throw invalidEndpoint(endpoint);
        }
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IOException("DNS server endpoint 无法解析: endpoint=" + endpoint);
        }
        return address;
    }

    private static IOException invalidEndpoint(String endpoint) {
        return invalidEndpoint(endpoint, null);
    }

    private static IOException invalidEndpoint(String endpoint, Throwable cause) {
        return new IOException("DNS server endpoint 格式无效: endpoint=" + endpoint, cause);
    }

    enum Status {
        EXISTS,
        INVALID,
        UNKNOWN
    }

    private static final class ResolverSlot {

        private final String endpoint;
        private final LookupSession session;
        private int currentWeight;
        private int timeoutStreak;
        private long openUntil;
        private boolean probeInFlight;

        private ResolverSlot(String endpoint, LookupSession session) {
            this.endpoint = endpoint;
            this.session = session;
        }

        private String endpoint() {
            return endpoint;
        }

        private LookupSession session() {
            return session;
        }

        private boolean available(long now) {
            if (openUntil == 0) {
                return true;
            }
            if (now < openUntil) {
                return false;
            }
            if (probeInFlight) {
                return false;
            }
            return true;
        }

        private void beginProbe() {
            if (openUntil != 0) {
                probeInFlight = true;
            }
        }

        private boolean isOpen() {
            return openUntil != 0;
        }

        private boolean isHalfOpen() {
            return openUntil != 0 && probeInFlight;
        }

        private void addCurrentWeight() {
            currentWeight++;
        }

        private int currentWeight() {
            return currentWeight;
        }

        private void subtractCurrentWeight(int available) {
            currentWeight -= available;
        }

        private void markSuccess() {
            timeoutStreak = 0;
            openUntil = 0;
            probeInFlight = false;
        }

        private void markFailure() {
            probeInFlight = false;
        }

        private void markTimeout(long now, boolean wasHalfOpen) {
            probeInFlight = false;
            if (wasHalfOpen || ++timeoutStreak >= TIMEOUT_BREAKER_THRESHOLD) {
                timeoutStreak = 0;
                openUntil = now + TIMEOUT_BREAKER_DURATION.toNanos();
                currentWeight = 0;
            }
        }
    }
}

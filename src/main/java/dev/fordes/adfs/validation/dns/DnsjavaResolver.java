package dev.fordes.adfs.validation.dns;

import dev.fordes.adfs.config.EffectiveConfig.DnsCacheConfig;
import dev.fordes.adfs.config.EffectiveConfig.DnsConfig;
import dev.fordes.adfs.error.DnsException;
import dev.fordes.adfs.rule.model.DomainName;
import lombok.extern.slf4j.Slf4j;
import org.xbill.DNS.*;
import org.xbill.DNS.lookup.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class DnsjavaResolver implements DnsResolver {

    private static final int DEFAULT_DNS_PORT = 53;
    private final DnsConfig config;
    private final Cache cache;
    private final LookupSession session;
    private final AtomicLong retryCount = new AtomicLong();

    public DnsjavaResolver(DnsConfig config) {
        this.config = config;
        this.cache = createCache(config.cache());
        this.session = createSession(config, cache);
    }

    @Override
    public DnsResult resolve(DomainName domain) {
        Name name;
        try {
            name = Name.fromString(domain.value() + ".");
        } catch (TextParseException exception) {
            throw new DnsException("规范域名无法转换为 DNS Name: domain=" + domain.value(), exception);
        }
        LookupFailedException lastFailure = new LookupFailedException(name, Type.A);
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            try {
                return queryAddress(name);
            } catch (LookupFailedException exception) {
                lastFailure = exception;
                if (attempt == config.retries()) {
                    break;
                }
                retryCount.incrementAndGet();
                log.debug("DNS 重试:  {} --> 第 {} 次尝试，最多尝试 {} 次",
                        domain.value(), attempt + 2, config.retries() + 1);
            }
        }
        throw new DnsException("DNS 查询重试耗尽: domain=" + domain.value() + ", query=A/AAAA, cause="
                + lastFailure.getClass().getSimpleName() + ": " + lastFailure.getMessage(), lastFailure);
    }

    private DnsResult queryAddress(Name name) {
        try {
            LookupResult result = await(name, Type.A);
            if (!result.getRecords().isEmpty()) {
                return DnsResult.VALID;
            }
        } catch (NoSuchDomainException _) {
            return DnsResult.INVALID;
        } catch (NoSuchRRSetException _) {
            // A 不存在时继续查询 AAAA。
        }
        try {
            LookupResult result = await(name, Type.AAAA);
            return result.getRecords().isEmpty() ? DnsResult.INVALID : DnsResult.VALID;
        } catch (NoSuchDomainException | NoSuchRRSetException _) {
            return DnsResult.INVALID;
        }
    }

    private LookupResult await(Name name, int type) {
        try {
            LookupResult result = session.lookupAsync(name, type).toCompletableFuture().join();
            if (log.isTraceEnabled()) {
                log.trace("DNS 解析成功:  {} ({}) --> {}", name, Type.string(type),
                        result.getRecords().stream().map(record -> record.rdataToString()).toList());
            }
            return result;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof NoSuchDomainException) {
                if (log.isTraceEnabled()) {
                    log.trace("DNS 解析完成:  {} ({}) --> 域名不存在(NXDOMAIN)", name, Type.string(type));
                }
            } else if (cause instanceof NoSuchRRSetException) {
                if (log.isTraceEnabled()) {
                    log.trace("DNS 解析完成:  {} ({}) --> 没有对应记录", name, Type.string(type));
                }
            } else if (log.isDebugEnabled()) {
                log.debug("DNS 解析失败:  {} ({}) --> {}",
                        name, Type.string(type), cause.toString());
            }
            if (cause instanceof LookupFailedException lookupFailure) {
                throw lookupFailure;
            }
            throw new DnsException("DNS 异步查询异常: name=" + name + ", type=" + Type.string(type), cause);
        }
    }

    @Override
    public int cacheSize() {
        return cache.getSize();
    }

    @Override
    public long retries() {
        return retryCount.get();
    }

    private static Cache createCache(DnsCacheConfig config) {
        Cache cache = new Cache(DClass.IN);
        cache.setMaxEntries(config.maxEntries());
        cache.setMaxCache(toSeconds(config.maxTtl()));
        cache.setMaxNCache(toSeconds(config.maxNegativeTtl()));
        return cache;
    }

    private static LookupSession createSession(DnsConfig config, Cache cache) {
        Resolver resolver = config.servers().isEmpty() ? createSystemResolver(config.timeout())
                : createConfiguredResolver(config.servers(), config.timeout());
        return LookupSession.builder()
                .resolver(resolver)
                .cache(cache)
                .clearSearchPath()
                .maxRedirects(config.maxCnameDepth())
                .build();
    }

    private static Resolver createSystemResolver(Duration timeout) {
        try {
            SimpleResolver resolver = new SimpleResolver();
            resolver.setTimeout(timeout);
            return resolver;
        } catch (UnknownHostException exception) {
            throw new DnsException("无法初始化系统 DNS 解析器", exception);
        }
    }

    private static Resolver createConfiguredResolver(List<String> servers, Duration timeout) {
        List<Resolver> resolvers = new ArrayList<>();
        for (String server : servers) {
            InetSocketAddress address = parseServer(server);
            SimpleResolver resolver = new SimpleResolver(address);
            resolver.setTimeout(timeout);
            resolvers.add(resolver);
        }
        ExtendedResolver resolver = new ExtendedResolver(resolvers);
        resolver.setRetries(1);
        resolver.setTimeout(timeout);
        return resolver;
    }

    private static InetSocketAddress parseServer(String server) {
        String address;
        int port = DEFAULT_DNS_PORT;
        if (server.startsWith("[")) {
            int end = server.indexOf(']');
            address = server.substring(1, end);
            if (end + 1 < server.length()) {
                port = Integer.parseInt(server.substring(end + 2));
            }
        } else {
            int separator = server.lastIndexOf(':');
            if (separator >= 0) {
                address = server.substring(0, separator);
                port = Integer.parseInt(server.substring(separator + 1));
            } else {
                address = server;
            }
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(address), port);
        } catch (UnknownHostException exception) {
            throw new DnsException("已校验的 DNS 地址无法解析: server=" + server, exception);
        }
    }

    private static int toSeconds(Duration duration) {
        return Math.toIntExact(duration.toSeconds());
    }
}

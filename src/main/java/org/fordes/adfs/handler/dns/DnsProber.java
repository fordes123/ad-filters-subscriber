package org.fordes.adfs.handler.dns;

import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.ParserProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.fordes.adfs.config.ParserProperties.DnsProvide;

@Data
@Slf4j
@Component
@ConditionalOnProperty(prefix = "application.config.parser.dns-probe", name = "enable", havingValue = "true")
public class DnsProber {

    private final MultiThreadIoEventLoopGroup group;
    private final List<DnsNameResolver> resolvers;
    private final AtomicLong nextIndex;
    private final LongAdder cacheHits;
    private final int cpu = Runtime.getRuntime().availableProcessors();

    public DnsProber(ParserProperties properties) {
        var config = properties.dnsProbe();

        int threadNum = Runtime.getRuntime().availableProcessors();
        int resolverNum = Math.min(4, threadNum);

        this.group = new MultiThreadIoEventLoopGroup(threadNum, NioIoHandler.newFactory());
        this.nextIndex = new AtomicLong(0);
        this.cacheHits = new LongAdder();


        //初始化
        DnsServerAddressStreamProvider provider = buildProvider(config.provider());
        DnsCache sharedCache = this.buildDnsCache(config.cacheTtlMin(), config.cacheTtlMax(), config.cacheNegativeTtl());
        DnsCnameCache sharedCnameCache = new DefaultDnsCnameCache((int) config.cacheTtlMin().toSeconds(), (int) config.cacheTtlMax().toSeconds());
        this.resolvers = IntStream.range(0, resolverNum).mapToObj(i -> {
            EventLoop loop = group.next();
            DnsNameResolver resolver = new DnsNameResolverBuilder(loop)
                    .datagramChannelFactory(NioDatagramChannel::new)
                    .nameServerProvider(provider)
                    .queryTimeoutMillis(config.timeout().toMillis())
                    .maxQueriesPerResolve(2)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                    .resolveCache(sharedCache)
                    .cnameCache(sharedCnameCache)
                    .optResourceEnabled(false)
                    .build();
            return resolver;
        }).toList();

        log.info("dns detector init success, thread: {} parallel: {}", cpu, threadNum);
    }

    private DnsServerAddressStreamProvider buildProvider(Collection<DnsProvide> provider) {
        if (provider.isEmpty()) {
            return DnsServerAddressStreamProviders.platformDefault();
        }

        InetSocketAddress[] addresses = provider.stream()
                .map(e -> {
                    if (e.port() == null) {
                        return new InetSocketAddress(e.host(), 53);
                    }
                    return new InetSocketAddress(e.host(), e.port());
                })
                .toArray(InetSocketAddress[]::new);

        return new SequentialDnsServerAddressStreamProvider(addresses);
    }

    private DnsCache buildDnsCache(Duration minTtl, Duration maxTtl, Duration negativeTtl) {
        return new DefaultDnsCache((int) minTtl.getSeconds(), (int) maxTtl.getSeconds(), (int) negativeTtl.getSeconds()) {
            @Override
            public List<? extends DnsCacheEntry> get(String hostname, DnsRecord[] additionals) {
                List<? extends DnsCacheEntry> result = super.get(hostname, additionals);
                if (result != null && !result.isEmpty()) {
                    cacheHits.increment();
                }
                return result;
            }
        };
    }

    public Mono<Boolean> lookup(String domain) {
        if (domain == null || domain.isEmpty()) {
            return Mono.just(true);
        }

        String normalizedDomain = domain.toLowerCase().trim();
        DnsNameResolver resolver = resolvers.get((int) (nextIndex.getAndIncrement() % resolvers.size()));
        return lookup(resolver, normalizedDomain);
    }

    private Mono<Boolean> lookup(DnsNameResolver resolver, String domain) {
        return Mono.create(sink -> {

            Future<List<InetAddress>> future = resolver.resolveAll(domain);
            future.addListener(result -> {

                boolean res = true;
                if (!result.isSuccess()) {
                    Throwable cause = result.cause();

                    if (cause instanceof UnknownHostException) {
                        res = false;
                    } else {
                        log.warn("dns check failed: {} => {}", domain, cause.getMessage());
                    }
                }
                sink.success(res);
                log.debug("dns check done, available: {}", resolvers.size());
            });
        });
    }


    @PreDestroy
    public void destroy() {
        try {
            log.info("dns detector shutdown, total queries:{}, cache hits: {}", nextIndex.get(), cacheHits.sum());

            resolvers.forEach(DnsNameResolver::close);
            group.shutdownGracefully().await(10, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Error during shutdown", e);
        }
    }
}
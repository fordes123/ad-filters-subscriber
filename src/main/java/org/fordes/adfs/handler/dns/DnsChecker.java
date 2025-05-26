package org.fordes.adfs.handler.dns;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

@Data
@Slf4j
@Component
@EnableConfigurationProperties(DnsChecker.Config.class)
public class DnsChecker {

    private final Config config;
    private final DnsNameResolver resolver;
    private final NioEventLoopGroup eventLoopGroup;

    public DnsChecker(Config config) {
        this.config = config;
        if (config.enable) {
            this.eventLoopGroup = new NioEventLoopGroup(config.eventLoop);
            InetSocketAddress[] array = config.provider.stream().map(e -> {
                String[] split = e.split(":");
                return new InetSocketAddress(split[0], split.length > 1 ? Integer.parseInt(split[1]) : 53);
            }).toArray(InetSocketAddress[]::new);

            this.resolver = new DnsNameResolverBuilder(eventLoopGroup.next())
                    .channelType(NioDatagramChannel.class)
                    .nameServerProvider(new SequentialDnsServerAddressStreamProvider(array))
                    .queryTimeoutMillis(config.timeout)
                    .maxQueriesPerResolve(1)
                    .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                    .build();
        } else {
            log.warn("dns check is disabled");
            this.resolver = null;
            this.eventLoopGroup = null;
        }
    }

    @ConfigurationProperties(prefix = "application.config.domain-detect")
    public record Config(Boolean enable, Integer timeout, Integer eventLoop, List<String> provider) {

        public Config(Boolean enable, Integer timeout, Integer eventLoop, List<String> provider) {
            this.enable = Optional.ofNullable(enable).orElse(Boolean.TRUE);
            this.timeout = Optional.ofNullable(timeout).orElse(1000);
            this.eventLoop = Optional.ofNullable(eventLoop).orElse(4);
            this.provider = Optional.ofNullable(provider).filter(e -> !e.isEmpty()).orElse(List.of("8.8.8.8"));
        }
    }

    public Mono<Boolean> isDomainValid(String domain) {
        return Mono.create(sink -> {

            if (resolver == null) {
                sink.success(true);
                return;
            }

            try {
                Future<InetAddress> future = resolver.resolve(domain);
                future.addListener(f -> {

                    if (!f.isSuccess()) {

                        Throwable cause = f.cause();
                        if (cause instanceof UnknownHostException) {
                            sink.success(false);
                        }
                    }

                    sink.success(true);
                });
            } catch (Exception e) {
                log.warn("dns check filed: {}", domain, e);
                sink.success(true);
            }
        });
    }
}
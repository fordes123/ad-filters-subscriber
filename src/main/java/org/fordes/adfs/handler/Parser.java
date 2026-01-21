package org.fordes.adfs.handler;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.AdFSApplication;
import org.fordes.adfs.config.FetcherProperties;
import org.fordes.adfs.config.ParserProperties;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.handler.dns.DnsProber;
import org.fordes.adfs.handler.fetch.Fetcher;
import org.fordes.adfs.handler.rule.Handler;
import org.fordes.adfs.model.Rule;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

import static org.fordes.adfs.config.InputProperties.Item;

@Slf4j
@Component
public class Parser {

    private final LongOpenHashSet hashSet;
    private final ParserProperties parserProperties;
    private final FetcherProperties fetcherProperties;
    protected final Optional<DnsProber> dnsProber;

    public Parser(ParserProperties parserProperties, FetcherProperties fetcherProperties, Optional<DnsProber> dnsProber) {

        this.parserProperties = parserProperties;
        this.fetcherProperties = fetcherProperties;
        this.dnsProber = dnsProber;
        this.hashSet = new LongOpenHashSet(parserProperties.expected());
    }

    public Flux<Rule> handle(Item prop) {
        if (prop.path().startsWith("http")) {
            return this.handle(prop, HandleType.REMOTE);
        }
        return this.handle(prop, HandleType.LOCAL);
    }

    public Flux<Rule> handle(Item prop, HandleType type) {

        LongAdder total = new LongAdder();
        LongAdder invalid = new LongAdder();
        LongAdder repeat = new LongAdder();
        LongAdder effective = new LongAdder();

        long start = System.currentTimeMillis();

        return Flux.just(prop)
                .flatMap(p -> {
                    Fetcher fetcher = Fetcher.getFetcher(type, fetcherProperties);
                    return fetcher.fetch(p.path());
                })
                .filter(StringUtils::hasText)
                .flatMap(line -> {
                    Handler handler = Handler.getHandler(prop.type());

                    if (handler.isComment(line)) {
                        log.trace("[{}] comment line: {}", prop.name(), line);
                        return Mono.empty();
                    }

                    if (parserProperties.minLength() > 0 && line.length() < parserProperties.minLength()) {
                        log.warn("[{}] rule too short: {}", prop.name(), line);
                        return Mono.empty();
                    }

                    if (parserProperties.maxLength() > 0 && line.length() > parserProperties.maxLength()) {
                        log.warn("[{}] rule too long: {}", prop.name(), line);
                        return Mono.empty();
                    }

                    Rule rule = handler.parse(line);
                    total.increment();
                    if (Rule.EMPTY.equals(rule)) {
                        invalid.increment();
                        log.warn("[{}] unresolvable: {}", prop.name(), line);
                        return Mono.empty();
                    }

                    rule.setSourceName(prop.name());
                    return Mono.just(rule);
                })
                .flatMap(e -> {

                    if (e.getTarget() != null && parserProperties.excludes().contains(e.getTarget())) {
                        log.info("[{}] excluded: {}", prop.name(), e.getOrigin());
                        return Mono.empty();
                    }

                    if (e.getOrigin().length() <= parserProperties.alertLength()) {
                        log.warn("[{}] suspicious rule => {}", prop.name(), e.getOrigin());
                    }

                    return Mono.just(e);

                })
                .onErrorResume(ex -> {
                    log.error("[{}] parse error: {}", prop.name(), ex.getMessage());
                    return Mono.empty();
                })
                .flatMap(rule -> {

                    /**
                     * 假设有规则 ||example.org^
                     * 通过DNS查询 example.org 是否存在 A/AAAA/CNAME 记录作为判断依据
                     * 不可避免的误判是，example.org 没有有效记录，而其存在有效子域如 test.example.org
                     */
                    return this.dnsProber.map(d -> {

                        if (Rule.Type.BASIC.equals(rule.getType())
                                && Rule.Scope.DOMAIN.equals(rule.getScope())) {
                            return Flux.just(rule.getTarget())
                                    .flatMap(d::lookup, 1)
                                    .flatMap(e -> {
                                        if (!e) {
                                            invalid.increment();
                                            log.warn("[{}] dns check nopass: {}", prop.name(), rule.getOrigin());
                                            return Mono.empty();
                                        }
                                        return Mono.just(rule);
                                    });
                        }
                        return Mono.just(rule);
                    }).orElse(Mono.just(rule));

                }, parserProperties.dnsProbe().parallel())
                .flatMap(e -> {

                    long hash = e.murmur3Hash();
                    synchronized (hashSet) {
                        if (!hashSet.add(hash)) {
                            log.info("[{}] already exists: {}", prop.name(), e.getOrigin());
                            repeat.increment();
                            return Mono.empty();
                        }
                    }

                    effective.increment();
                    log.trace("[{}] effctive: {}", prop.name(), e);
                    return Mono.just(e);

                })
                .doFinally(signal -> {
                    AdFSApplication.log.info("[{}] parsing cost {} ms, total: {}, effective: {}, repeat: {}, invalid: {}",
                            prop.name(), System.currentTimeMillis() - start,
                            total.longValue(), effective.longValue(), repeat.longValue(), invalid.longValue());
                });

    }
}

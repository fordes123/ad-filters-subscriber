package org.fordes.adfs.handler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.Config;
import org.fordes.adfs.config.InputProperties;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.handler.dns.DnsResolver;
import org.fordes.adfs.handler.rule.Handler;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.task.FileWriter;
import org.fordes.adfs.util.BloomFilter;
import org.springframework.beans.factory.InitializingBean;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则处理器抽象
 *
 * @author fordes on 2022/7/7
 */
@Slf4j
@AllArgsConstructor
public abstract class RuleHandler implements InitializingBean {

    protected final BloomFilter<Rule> filter;
    protected FileWriter writer;
    protected Config config;
    protected DnsResolver dnsResolver;
    protected static final Map<HandleType, RuleHandler> handlerMap = new HashMap<>(HandleType.values().length);

    protected final void register(HandleType type, RuleHandler handler) {
        handlerMap.put(type, handler);
    }

    public void handle(InputProperties.Prop prop) {
        AtomicLong invalid = new AtomicLong(0L);
        AtomicLong repeat = new AtomicLong(0L);
        AtomicLong effective = new AtomicLong(0L);

        try (InputStream is = getStream(prop.path());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String original = line;
                Optional.of(line.trim())
                        .filter(e -> {
                            if (e.isBlank() || Handler.getHandler(prop.type()).isComment(e)) {
                                invalid.incrementAndGet();
                                return Boolean.FALSE;
                            }
                            return Boolean.TRUE;
                        })
                        .map(e -> Handler.getHandler(prop.type()).parse(e))
                        .filter(e -> Optional.of(!filter.contains(e))
                                .filter(t -> t)
                                .orElseGet(() -> {
                                    log.debug("already exists rule: {}", original);
                                    repeat.incrementAndGet();
                                    return Boolean.FALSE;
                                }))

                        //域名检测
                        .filter(e -> dnsResolver.apply(e))
                        //写入阻塞队列
                        .ifPresent(e -> {

                            if (original.length() <= config.getWarnLimit()) {
                                log.warn("[{}] Suspicious rule => {}", prop.name(), original);
                            }

                            filter.add(e);
                            effective.incrementAndGet();
                            writer.put(e);
                        });
            }
        } catch (Exception e) {
            log.error("[{}] parser failed  => {}\n", prop.name(), e.getMessage(), e);
        }


        log.info("[{}]  parser done => invalid: {}, repeat: {}, effective: {}", prop.name(),
                invalid.get(), repeat.get(), effective.get());
    }

    public static RuleHandler getHandler(HandleType type) {
        return handlerMap.get(type);
    }

    protected abstract InputStream getStream(String path);

    protected abstract Charset getCharset();
}

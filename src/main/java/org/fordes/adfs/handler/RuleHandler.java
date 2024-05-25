package org.fordes.adfs.handler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.Config;
import org.fordes.adfs.config.InputProperties;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.enums.RuleType;
import org.fordes.adfs.task.FileWriter;
import org.fordes.adfs.util.BloomFilter;
import org.fordes.adfs.util.Util;
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

    protected BloomFilter<String> filter;
    protected FileWriter writer;
    protected Config config;
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
                Optional.of(line)
                        .map(Util::clearRule)
                        //去除无效规则
                        .filter(e -> Optional.of(!e.isEmpty())
                                .filter(t -> t)
                                .orElseGet(() -> {
                                    invalid.incrementAndGet();
                                    return Boolean.FALSE;
                                }))
                        //解析规则类型
                        .map(e -> Map.entry(e, Util.validRule(e)))
                        //除了 modify 类型，原始规则长度不能超过1024 (https://github.com/AdguardTeam/AdGuardHome/issues/6003)
                        .filter(e -> Optional.of(RuleType.MODIFY.equals(e.getValue()) || original.length() <= 1024)
                                .filter(t -> t)
                                .orElseGet(() -> {
                                    invalid.incrementAndGet();
                                    log.debug("invalid rule: {}: Length must be less than 1024", original);
                                    return Boolean.FALSE;
                                }))
                        //过滤重复规则
                        .filter(e -> Optional.of(!filter.contains(original))
                                .filter(t -> t)
                                .orElseGet(() -> {
                                    log.debug("already exists rule: {}", original);
                                    repeat.incrementAndGet();
                                    return Boolean.FALSE;
                                }))
                        //写入阻塞队列
                        .ifPresent(e -> {

                            if (original.length() <= config.getWarnLimit()) {
                                log.warn("[{}] Suspicious rule => {}",prop.name(), original);
                            }

                            filter.add(original);
                            effective.incrementAndGet();
                            writer.put(original, e.getValue());
                        });
            }
        } catch (Exception e) {
            log.error("[{}] parser failed  => {}", prop.name(), e.getMessage());
        }


        log.info("[{}]  parser done => {}/{}/{}", prop.name(),
                invalid.get(), repeat.get(), effective.get());
    }

    public static RuleHandler getHandler(HandleType type) {
        return handlerMap.get(type);
    }

    protected abstract InputStream getStream(String path);

    protected abstract Charset getCharset();
}

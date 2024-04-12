package org.fordes.adfs.handler;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.task.FileWriter;
import org.fordes.adfs.util.BloomFilter;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 本地规则处理
 *
 * @author fordes on 2022/7/7
 */
@Slf4j
@EnableAsync
@Component
public class LocalRuleHandler extends RuleHandler {

    @Autowired
    public LocalRuleHandler(BloomFilter<String> filter, FileWriter writer) {
        super(filter, writer);
    }

    @Override
    protected void getStream(String path, Consumer<InputStream> consumer) {
        Optional.ofNullable(path)
                .filter(StringUtils::hasText)
                .ifPresent(p -> {
                    String absPath = Util.normalizePath(p);;
                    try (InputStream is = Files.newInputStream(Path.of(absPath), StandardOpenOption.READ)) {
                        Optional.of(is).ifPresent(consumer);
                    } catch (Exception e) {
                        log.error("local rule => {}, read failed  => {}", path, e.getMessage());
                    }
                });
    }

    @Override
    protected Charset getCharset() {
        return Charset.defaultCharset();
    }

    @Override
    public void afterPropertiesSet() {
        register(HandleType.LOCAL, this);
    }
}

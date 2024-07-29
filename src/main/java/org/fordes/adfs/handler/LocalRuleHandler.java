package org.fordes.adfs.handler;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.Config;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.handler.dns.DnsResolver;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.task.FileWriter;
import org.fordes.adfs.util.BloomFilter;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地规则处理
 *
 * @author fordes on 2022/7/7
 */
@Slf4j
@Component
public class LocalRuleHandler extends RuleHandler {

    @Autowired
    public LocalRuleHandler(BloomFilter<Rule> filter, FileWriter writer, Config config, DnsResolver dnsResolver) {
        super(filter, writer, config, dnsResolver);
    }

    @Override
    protected InputStream getStream(String path) {
        try {
            if (StringUtils.hasText(path)) {
                String absPath = Util.normalizePath(path);
                return Files.newInputStream(Path.of(absPath), StandardOpenOption.READ);
            }
        } catch (Exception e) {
            log.error("local rule => {}, read failed  => {}", path, e.getMessage());
        }
        return InputStream.nullInputStream();
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

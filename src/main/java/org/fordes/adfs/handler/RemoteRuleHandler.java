package org.fordes.adfs.handler;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.Config;
import org.fordes.adfs.enums.HandleType;
import org.fordes.adfs.handler.dns.DnsResolver;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.task.FileWriter;
import org.fordes.adfs.util.BloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;

@Slf4j
@Component
public class RemoteRuleHandler extends RuleHandler {

    private final Charset charset = Charset.defaultCharset();
    public static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Autowired
    public RemoteRuleHandler(BloomFilter<Rule> filter, FileWriter writer, Config config, DnsResolver dnsResolver) {
        super(filter, writer, config, dnsResolver);
    }

    @Override
    protected InputStream getStream(String path) {
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest
                            .newBuilder(URI.create(path))
                            .GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                return response.body();
            }
            throw new RuntimeException("http status code: " + response.statusCode());
        } catch (Exception e) {
            log.error("remote rule => {}, get failed  => {}", path, e.getMessage());
        }
        return InputStream.nullInputStream();
    }

    @Override
    protected Charset getCharset() {
        return charset;
    }

    @Override
    public void afterPropertiesSet() {
        register(HandleType.REMOTE, this);
    }
}

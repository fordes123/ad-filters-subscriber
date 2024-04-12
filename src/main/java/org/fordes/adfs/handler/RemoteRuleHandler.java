package org.fordes.adfs.handler;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.enums.HandleType;
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
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Component
public class RemoteRuleHandler extends RuleHandler {

    private final Charset charset = Charset.defaultCharset();

    @Autowired
    public RemoteRuleHandler(BloomFilter<String> filter, FileWriter writer) {
        super(filter, writer);
    }

    @Override
    protected void getStream(String path, Consumer<InputStream> consumer) {

        try(HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build()) {
            HttpResponse<InputStream> response = client.send(HttpRequest
                            .newBuilder(URI.create(path))
                            .GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Optional.of(response.body()).ifPresent(consumer);
            }
            throw new RuntimeException(String.valueOf(response.statusCode()));
        } catch (Exception e) {
            log.error("remote rule => {}, get failed  => {}", path, e.getMessage());
        }
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

package org.fordes.adfs.handler.dns;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.model.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

/**
 * @author Chengfs on 2024/7/29
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "application.config.domain-detect")
public class DnsResolver implements Function<Rule, Boolean> {

    private Boolean enable = Boolean.TRUE;
    private final String dns = "https://dns.google/resolve";
    private Integer timeout = 3;
    private static final HttpClient client;
    private static ObjectMapper mapper;

    static {
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }


    public Result resolve(String domain) {

        try {
            String url = dns + "?name=" + domain;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/dns-json")
                    .GET()
                    .timeout(Duration.ofSeconds(timeout))
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return mapper.readValue(response.body(), Result.class);
        } catch (Exception e) {
            log.warn("dns resolve failed {} => {}", domain, e.getMessage());
            return Result.unknown();
        }
    }

    @Override
    public Boolean apply(Rule rule) {

        if (enable && Rule.Type.BASIC.equals(rule.getType()) && Rule.Scope.DOMAIN.equals(rule.getScope())) {
            Result result = resolve(rule.getTarget());
            if (result.Status() == 3) {
                log.debug("detected NXDOMAIN => {}", rule.getTarget());
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    public record Result(Integer Status) {

        //此状态不属于标准dns响应，用于程序无法获取dns响应状态时回退
        public static Result unknown() {
            return new Result(-1);
        }
    }
}
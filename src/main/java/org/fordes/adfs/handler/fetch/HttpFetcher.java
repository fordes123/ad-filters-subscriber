package org.fordes.adfs.handler.fetch;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.fordes.adfs.config.FetcherProperties.HttpProperty;

@Slf4j
public class HttpFetcher extends Fetcher {

    private final WebClient webClient;
    private final HttpProperty property;

    public HttpFetcher(HttpProperty property) {
        this.property = property;
        final HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) property.connectTimeout().toMillis())
                .responseTimeout(property.responseTimeout())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(property.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(property.writeTimeout().toMillis(), TimeUnit.MILLISECONDS))
                );

        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize((int) property.bufferSize().toBytes())
                )
                .build();

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.CONNECTION, "keep-alive")
//                .defaultHeader(HttpHeaders.ACCEPT_CHARSET, this.charset().displayName())
//                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br")
                .build();
    }


    @Override
    public Flux<String> fetch(String path) {
        Flux<DataBuffer> data = webClient.get()
                .uri(URI.create(path))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(t -> {
                            if (t instanceof IOException) return true;
                            if (t instanceof WebClientResponseException e) {
                                return e.getStatusCode().is5xxServerError();
                            }
                            return false;
                        })
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                            return retrySignal.failure();
                        }))
                .onErrorResume(e -> {
                    log.error("http rule => {}, fetch failed  => {}", path, e.getMessage(), e);
                    return Flux.empty();
                });

        return this.fetch(data);
    }

    @Override
    protected Charset charset() {
        return this.property.charset();
    }

}

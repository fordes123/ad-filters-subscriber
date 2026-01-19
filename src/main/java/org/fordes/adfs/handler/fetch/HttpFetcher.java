package org.fordes.adfs.handler.fetch;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpFetcher extends Fetcher {

    private final WebClient webClient;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration writeTimeout = Duration.ofSeconds(30);
    private Duration responseTimeout = Duration.ofSeconds(30);
    private DataSize bufferSize = DataSize.ofBytes(4096);

    public HttpFetcher() {

        final HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) this.connectTimeout.toMillis())
                .responseTimeout(this.responseTimeout)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(this.readTimeout.toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(this.writeTimeout.toMillis(), TimeUnit.MILLISECONDS))
                );

        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize((int) this.bufferSize.toBytes())
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

    public HttpFetcher(Duration connectTimeout, Duration readTimeout, Duration writeTimeout,
                       Duration responseTimeout, DataSize bufferSize) {
        this();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.responseTimeout = responseTimeout;
        this.writeTimeout = writeTimeout;
        this.bufferSize = bufferSize;
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
        return StandardCharsets.UTF_8;
    }

}

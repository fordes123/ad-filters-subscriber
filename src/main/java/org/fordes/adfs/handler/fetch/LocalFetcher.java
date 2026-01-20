package org.fordes.adfs.handler.fetch;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.util.Util;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.fordes.adfs.config.FetcherProperties.LocalProperty;

@Slf4j
public class LocalFetcher extends Fetcher {

    private final LocalProperty property;
    public LocalFetcher(LocalProperty property) {
        this.property = property;
    }

    @Override
    public Flux<String> fetch(String path) {

        Flux<DataBuffer> data = Flux.just(path)
                .map(Util::normalizePath)
                .map(Path::of)
                .flatMap(p -> DataBufferUtils.read(p, new DefaultDataBufferFactory(), (int) this.property.bufferSize().toBytes()))
                .onErrorResume(e -> {
                    log.error("local rule => {}, read failed  => {}", path, e.getMessage(), e);
                    return Flux.empty();
                });

        return this.fetch(data);
    }

    @Override
    protected Charset charset() {
        return this.property.charset();
    }

}

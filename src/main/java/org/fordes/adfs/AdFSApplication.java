package org.fordes.adfs;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.InputProperties;
import org.fordes.adfs.config.OutputProperties;
import org.fordes.adfs.constant.Constants;
import org.fordes.adfs.handler.Parser;
import org.fordes.adfs.handler.rule.Handler;
import org.fordes.adfs.model.Rule;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j(access = AccessLevel.PUBLIC)
@Component
@SpringBootApplication
@ConfigurationPropertiesScan(value = "org.fordes.adfs.config")
@RequiredArgsConstructor
public class AdFSApplication {

    private final ApplicationContext context;
    private final InputProperties input;
    private final OutputProperties output;
    private final Parser parser;
    private Map<String, Output> outputMap;

    public static void main(String[] args) {
        SpringApplication.run(AdFSApplication.class, args);
    }

    public record Output(OutputProperties.Item item, Path tempFile, AtomicLong count) {
    }

    @Bean
    public ApplicationRunner start() {

        // 创建临时文件
        this.outputMap = new HashMap<>(this.output.files().size(), 1);
        this.output.files().forEach(file -> {
            try {
                Path tempFile = Files.createTempFile(file.name(), ".tmp");
                this.outputMap.put(file.name(), new Output(file, tempFile, new AtomicLong(0L)));
            } catch (IOException e) {
                log.error("create temp file failed", e);
                this.exit();
            }
        });

        return args -> {
            long start = System.currentTimeMillis();


            Flux.fromIterable(this.input.input())
                    .flatMap(parser::handle, 1)
                    .flatMap(rule -> this.output(output.files(), rule))
                    .groupBy(Tuple2::getT1, Tuple2::getT2)
                    .flatMap(group -> group
                            .bufferTimeout(5000, Duration.ofSeconds(1))
                            .flatMap(batch -> asyncBatchWrite(group.key().name(), batch))
                            .subscribeOn(Schedulers.boundedElastic())
                    )
                    .then(Mono.defer(this::createOutputDirectory))
                    .then(Mono.defer(this::processOutputFiles))
                    .doOnError(ex -> {
                        log.error("processing failed", ex);
                        this.exit();
                    })
                    .doFinally(signal -> {
                        log.info("all done, cost: {} ms", System.currentTimeMillis() - start);
                        this.exit();
                    })
                    .subscribe();
        };
    }

    private Flux<Tuple2<OutputProperties.Item, String>> output(Set<OutputProperties.Item> outputs, Rule rule) {
        return Flux.fromIterable(outputs)
                .filter(file -> file.rule().isEmpty() || file.rule().contains(rule.getSourceName()))
                .filter(file -> file.filter().isEmpty() || file.filter().contains(rule.getType()))
                .flatMap(file -> {
                    Handler handler = Handler.getHandler(file.type());
                    String content = handler.format(rule);
                    return content != null ? Mono.just(Tuples.of(file, content)) : Mono.empty();
                });
    }

    private Mono<Path> createOutputDirectory() {
        return Mono.fromCallable(() -> Files.createDirectories(Path.of(output.path())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("create output dir failed", ex);
                    return Mono.error(ex);
                });
    }

    private Mono<Void> processOutputFiles() {
        Path dir = Path.of(output.path());
        return Flux.fromIterable(output.files())
                .flatMap(file -> {
                    Output opt = outputMap.get(file.name());
                    Path tempFile = opt.tempFile;
                    Path targetFile = dir.resolve(file.name());
                    String header = buildHeader(file, output.fileHeader(), Long.toString(opt.count.get()));

                    log.info("[{}] written completed, total size => {}", file.name(), opt.count.get());
                    return prependAndMove(targetFile, tempFile, header).subscribeOn(Schedulers.boundedElastic());
                })
                .then();
    }

    private Mono<Void> asyncBatchWrite(String fileName, List<String> batch) {
        Output opt = outputMap.get(fileName);
        opt.count().addAndGet(batch.size());

        return asyncBatchWrite(opt.tempFile, batch);
    }

    private Mono<Void> asyncBatchWrite(Path path, List<String> batch) {
        return Mono.fromCallable(() -> {
                    Files.write(path, batch, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return null;
                })
                .onErrorResume(e -> {
                    log.error("Write failed", e);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Path> prependAndMove(Path targetFile, Path tempFile, String header) {
        return Mono.fromCallable(() -> {
            if (Files.exists(targetFile)) {
                Path intermediateFile = Files.createTempFile(targetFile.getFileName().toString(), ".intermediate");

                try (BufferedWriter writer = Files.newBufferedWriter(intermediateFile, StandardCharsets.UTF_8);
                     BufferedReader tempReader = Files.newBufferedReader(tempFile, StandardCharsets.UTF_8)) {

                    if (!header.isBlank()) {
                        writer.write(header);
                    }

                    tempReader.transferTo(writer);
                }

                Files.move(intermediateFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tempFile);
            } else {
                try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

                    if (!header.isBlank()) {
                        List<String> lines = Files.readAllLines(tempFile, StandardCharsets.UTF_8);
                        Files.writeString(tempFile, header, StandardCharsets.UTF_8);
                        Files.write(tempFile, lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                }

                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return targetFile;
        });
    }

    private void exit() {
        //删除临时文件
        Optional.ofNullable(this.outputMap).ifPresent(map -> {
            map.forEach((k, v) -> {
                try {
                    Files.deleteIfExists(v.tempFile);
                } catch (IOException ignored) {
                    //ignore
                }
            });
        });
        int exit = SpringApplication.exit(this.context, () -> 0);
        System.exit(exit);
    }

    private String buildHeader(OutputProperties.Item config, String parentHeader, String total) {
        Handler handler = Handler.getHandler(config.type());
        StringBuilder builder = new StringBuilder();

        String template = config.fileHeader().isBlank() ? parentHeader : config.fileHeader();
        if (!template.isBlank()) {
            String header = handler.commented(template
                            .replace(Constants.Placeholder.HEADER_DATE, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                            .replace(Constants.Placeholder.HEADER_NAME, config.name())
                            .replace(Constants.Placeholder.HEADER_DESC, config.desc())
                            .replace(Constants.Placeholder.HEADER_TYPE, config.type().name().toLowerCase()))
                    .replace(Constants.Placeholder.HEADER_TOTAL, total);
            builder.append(header).append(System.lineSeparator());
        }

        Optional.ofNullable(handler.headFormat()).filter(StringUtils::hasText)
                .ifPresent(e -> builder.append(e).append(System.lineSeparator()));

        return builder.toString();
    }
}

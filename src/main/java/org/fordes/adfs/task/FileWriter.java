package org.fordes.adfs.task;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.config.OutputProperties;
import org.fordes.adfs.event.ExitEvent;
import org.fordes.adfs.event.StartEvent;
import org.fordes.adfs.event.StopEvent;
import org.fordes.adfs.handler.rule.Handler;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.fordes.adfs.constant.Constants.*;

/**
 * @author fordes on 2024/4/7
 */
@Slf4j
@Component
public class FileWriter {

    private final ApplicationEventPublisher publisher;
    private final OutputProperties output;
    private final ExecutorService consumer;

    protected Map<String, BlockingQueue<String>> fileQueueMap;
    private static Boolean stopFlag = null;

    @Autowired
    public FileWriter(ApplicationEventPublisher publisher, ExecutorService consumer, OutputProperties output) {
        this.publisher = publisher;
        this.consumer = consumer;
        this.output = output;

        Optional.ofNullable(output)
                .filter(e -> !e.isEmpty())
                .ifPresentOrElse(opt -> {
                    this.fileQueueMap = opt.getFiles()
                            .stream()
                            .map(e -> {
                                final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1000);
                                return Map.entry(e.name(), queue);
                            })
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                }, () -> {
                    log.error("no valid output configuration detected!");
                    publisher.publishEvent(new StopEvent(this));
                });
    }

    @EventListener(classes = StartEvent.class)
    public void start() {
        stopFlag = false;
        log.info("start file writer...");

        //创建输出目录
        final String dir = Util.normalizePath(output.getPath());
        Path dirPath = Path.of(dir);
        if (!Files.exists(dirPath)) {
            try {
                Files.createDirectories(dirPath);
            } catch (Exception e) {
                log.error("output path create failed => {}", dir, e);
            }
        }

        //消费阻塞队列
        output.getFiles().forEach(item -> {

            BlockingQueue<String> queue = fileQueueMap.get(item.name());
            Handler handler = Handler.getHandler(item.type());
            String fileName = item.name();

            consumer.execute(() -> {

                try (BufferedWriter writer = Files.newBufferedWriter(Path.of(dir + FILE_SEPARATOR + fileName),
                        StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

                    // 写入文件头
                    if (StringUtils.hasText(output.getFileHeader())) {
                        String header = handler.commented(output.getFileHeader()
                                .replace(HEADER_DATE, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                                .replace(HEADER_NAME, item.name())
                                .replace(HEADER_DESC, item.desc())
                                .replace(HEADER_TYPE, item.type().name().toLowerCase()));
                        writer.write(header, 0, header.length());
                        writer.newLine();
                    }

                    //写入格式头
                    String head = handler.headFormat();
                    if (StringUtils.hasText(head)) {
                        writer.write(head);
                        writer.newLine();
                    }

                    long total = 0;
                    while (!stopFlag || !queue.isEmpty()) {
                        String line = queue.poll();
                        if (line != null) {
                            writer.write(line);
                            total++;
                            writer.newLine();
                        } else {
                            Util.sleep(50);
                        }
                    }

                    //写入格式尾
                    String tail = handler.tailFormat();
                    if (StringUtils.hasText(tail)) {
                        writer.write(tail);
                        writer.newLine();
                    }

                    writer.flush();
                    log.info("file: {}, total => {}", fileName, total);
                } catch (Exception e) {
                    log.error("file writer error, fileName: {}", fileName, e);
                    publisher.publishEvent(new ExitEvent(this));
                }

            });
        });


    }

    @EventListener(classes = StopEvent.class)
    public void stop() {
        Optional.ofNullable(consumer).ifPresent(e -> {
            e.shutdown();
            stopFlag = true;

            while (!e.isTerminated()) {
                Util.sleep(300);
            }
        });

        // 消费者线程全部结束，发送退出事件
        log.info("file writer is done, wait exit...");
        publisher.publishEvent(new ExitEvent(this));
    }

    /**
     * 接收规则并写入阻塞队列
     *
     * @param rule {@link Rule}
     */
    public void put(Rule rule) {
        Optional.ofNullable(rule).ifPresent(r -> {

            output.getFiles()
                    .stream().filter(e -> e.filter().contains(rule.getType()))
                    .forEach(e -> {

                        BlockingQueue<String> queue = fileQueueMap.get(e.name());
                        Optional.ofNullable(Handler.getHandler(e.type()).format(r))
                                .filter(s -> !s.isEmpty())
                                .ifPresent(line -> {
                                    boolean flag = false;
                                    while (!flag) {
                                        flag = queue.offer(line);
                                        if (!flag) {
                                            Util.sleep(50);
                                        }
                                    }
                                });
                    });
        });
    }
}
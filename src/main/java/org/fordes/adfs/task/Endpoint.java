package org.fordes.adfs.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.Application;
import org.fordes.adfs.config.InputProperties;
import org.fordes.adfs.event.ExitEvent;
import org.fordes.adfs.event.StartEvent;
import org.fordes.adfs.event.StopEvent;
import org.fordes.adfs.handler.RuleHandler;
import org.fordes.adfs.util.Util;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author fordes on 2024/4/10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Endpoint {

    private final ApplicationEventPublisher publisher;
    private final ExecutorService producer;
    private final ThreadPoolTaskExecutor singleExecutor;
    private final InputProperties input;
    private static final AtomicBoolean exitFlag = new AtomicBoolean(false);

    @Bean
    @Order(Integer.MIN_VALUE)
    ApplicationRunner sentinel() {
        return args -> {
            //单线程监听 生产者状态和退出信号
            singleExecutor.submit(() -> {
                boolean isStop = false;
                while (!exitFlag.get()) {
                    if (!isStop && producer.isTerminated()) {
                        isStop = true;
                        log.info("rule parser all done, wait file writer...");
                        publisher.publishEvent(new StopEvent(this));
                    } else {
                        Util.sleep(300);
                    }
                }
            });
        };
    }

    @Bean
    ApplicationRunner init() {
        return args -> Optional.of(input)
                .filter(e -> !e.isEmpty())
                .ifPresentOrElse(in -> {

                            //提交任务
                            log.info("start rule parser...");
                            in.stream().forEach(e -> producer.submit(() -> {
                                RuleHandler handler = RuleHandler.getHandler(e.getKey());
                                handler.handle(e.getValue());
                            }));

                            //关闭生产者同时发布开始事件
                            producer.shutdown();
                            publisher.publishEvent(new StartEvent(this));
                        },
                        () -> {
                            log.error("no valid rule configuration detected!");
                            publisher.publishEvent(new ExitEvent(this));
                        }
                );
    }

    @EventListener(classes = ExitEvent.class)
    public void exist() {
        exitFlag.set(true);
        Optional.of(producer).filter(e -> !e.isTerminated()).ifPresent(ExecutorService::shutdownNow);
        Optional.of(singleExecutor).ifPresent(ExecutorConfigurationSupport::shutdown);
        Optional.ofNullable(Application.getContext()).ifPresent(ConfigurableApplicationContext::close);
    }
}
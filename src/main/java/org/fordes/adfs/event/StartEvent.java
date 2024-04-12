package org.fordes.adfs.event;

import org.springframework.context.ApplicationEvent;

/**
 * 此事件标志解析任务已开始，消费者可以开始工作
 *
 * @author fordes on 2024/4/9
 */
public class StartEvent extends ApplicationEvent {

    public StartEvent(Object source) {
        super(source);
    }
}
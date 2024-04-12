package org.fordes.adfs.event;

import org.springframework.context.ApplicationEvent;

/**
 * 此事件标志文件写入完毕，消费者已结束，程序可以退出
 *
 * @author fordes on 2024/4/9
 */
public class ExitEvent extends ApplicationEvent {

    public ExitEvent(Object source) {
        super(source);
    }
}
package org.fordes.adfs.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 此事件标志规则解析已完成，生产者已停止
 *
 * @author fordes on 2024/4/9
 */
@Getter
public class StopEvent extends ApplicationEvent {

    public StopEvent(Object source) {
        super(source);
    }
}
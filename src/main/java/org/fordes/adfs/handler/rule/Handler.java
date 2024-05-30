package org.fordes.adfs.handler.rule;

import jakarta.annotation.Nullable;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;

import java.util.HashMap;
import java.util.Map;

public abstract sealed class Handler permits EasylistHandler, DnsmasqHandler, ClashHandler,
        SmartdnsHandler, HostsHandler {

    private static final Map<RuleSet, Handler> handlerMap = new HashMap<>(RuleSet.values().length, 1);

    /**
     * 解析规则<br/>
     * 返回 null 即表示解析失败
     *
     * @param line 规则文本
     * @return {@link Rule}
     */
    public abstract @Nullable Rule parse(String line);

    /**
     * 转换规则<br/>
     * 如返回 null 即表示转换失败
     *
     * @param rule {@link Rule}
     * @return 规则文本
     */
    public abstract @Nullable String format(Rule rule);

    /**
     * 验证规则文本是否为注释<br/>
     * 并不强制子类实现此方法，且不是注释不表示此规则有效
     *
     * @param line 规则文本
     * @return 默认 false
     */
    public boolean isComment(String line) {
        return false;
    }

    /**
     * 根据 RuleSet 获取 Handler
     *
     * @param type {@link RuleSet}
     * @return {@link Handler}
     */
    public static Handler getHandler(RuleSet type) {
        return handlerMap.get(type);
    }

    protected void register(RuleSet type, Handler handler) {
        handlerMap.put(type, handler);
    }
}

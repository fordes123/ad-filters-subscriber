package org.fordes.adfs.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author fordes123 on 2022/9/19
 */
@Getter
@AllArgsConstructor
public enum RuleType {

    /**
     * 域名规则，形如 xxx.com、xx.oo.com
     */
    DOMAIN("域名规则"),

    /**
     * Hosts规则
     */
    HOSTS("Hosts规则"),

    /**
     * 正则规则，包含通配符(* ?)的域名规则
     */
    REGEX("正则规则"),

    /**
     * 修饰规则，添加了更多控制符号的规则
     */
    MODIFY("修饰规则");

    /**
     * 描述
     */
    private final String desc;
}

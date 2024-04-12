package org.fordes.adfs.constant;

import java.util.regex.Pattern;

/**
 * @author fordes on 2024/4/9
 */
public class RegConstants {

    /**
     * 基本的有效性检测正则，!开头，[]包裹，非特殊标记的#号开头均视为无效规则
     */
    public static final Pattern EFFICIENT_REGEX = Pattern.compile("^!|^#[^#,^@,^%,^\\$]|^\\[.*\\]$");

    /**
     * 去除首尾基础修饰符号 的正则，方便对规则进行分类
     * 包含：@@、||、@@||、/ 开头，$important、/ 结尾
     */
    public static final Pattern BASIC_MODIFY_REGEX = Pattern.compile("^@@\\|\\||^\\|\\||^@@|(\\^)?\\$important$|\\s#[^#]*$|\\^$");

    public static final Pattern PATTERN_PATH_ABSOLUTE = Pattern.compile("^[a-zA-Z]:([/\\\\].*)?");

    public static Pattern PATTERN_IP = Pattern.compile("((?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d))");
    public static Pattern PATTERN_DOMAIN = Pattern.compile("(?=^.{3,255}$)[a-zA-Z0-9][-a-zA-Z0-9]{0,62}(\\.[a-zA-Z0-9][-a-zA-Z0-9]{0,62})+$");
}
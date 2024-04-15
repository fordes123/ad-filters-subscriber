package org.fordes.adfs.util;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.constant.RegConstants;
import org.fordes.adfs.enums.RuleType;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;

import static org.fordes.adfs.constant.Constants.*;
import static org.fordes.adfs.constant.RegConstants.*;

/**
 * @author fordes123 on 2022/9/19
 */
@Slf4j
public class Util {

    public static RuleType validRule(String rule) {

        Matcher matcher = PATTERN_IP.matcher(rule);
        if (matcher.matches()) {
            return RuleType.MODIFY;
        } else if (rule.startsWith(LOCALHOST_V6) || matcher.find()) {
            String pure = matcher.replaceAll(EMPTY).replace(LOCALHOST_V6, EMPTY);
            if (LOCALHOST.equals(pure.trim()) || PATTERN_DOMAIN.matcher(pure.trim()).matches()) {
                return RuleType.HOSTS;
            }
        }

        if (PATTERN_DOMAIN.matcher(rule).matches()) {
            return RuleType.DOMAIN;
        }

        String pure = rule.replace(ASTERISK, A).replace(QUESTION_MARK, A);
        if (PATTERN_DOMAIN.matcher(pure).matches()) {
            return RuleType.REGEX;
        }

        return RuleType.MODIFY;

    }

    /**
     * 清理rule字符串，去除空格和某些特定符号
     *
     * @param content 内容
     * @return 结果
     */
    public static String clearRule(String content) {
        content = StringUtils.hasText(content) ? content.trim() : EMPTY;

        //有效性检测
        if (EFFICIENT_REGEX.matcher(content).find()) {
            log.debug("invalid rule: {}: Detect as Comments", content);
            return EMPTY;
        }
        if (content.length() >= 1024) {
            log.debug("invalid rule: {}: Length must be less than 1024", content);
            return EMPTY;
        }

        //去除首尾 基础修饰符号
        content = BASIC_MODIFY_REGEX.matcher(content).replaceAll(EMPTY);

        return content.trim();
    }

    public static void sleep(long millis) {
        if (millis > 0L) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public static String normalizePath(String path) {
        boolean isAbsPath = '/' == path.charAt(0) ||
                RegConstants.PATTERN_PATH_ABSOLUTE.matcher(path).matches();

        if (!isAbsPath) {
            if (path.startsWith(DOT)) {
                path = path.substring(1);
            }
            if (path.startsWith(FILE_SEPARATOR)) {
                path = path.substring(FILE_SEPARATOR.length());
            }
            path = ROOT_PATH + FILE_SEPARATOR + path;
        }
        return path;
    }
}

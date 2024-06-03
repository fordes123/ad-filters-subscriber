package org.fordes.adfs.util;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.constant.RegConstants;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.fordes.adfs.constant.Constants.*;
import static org.fordes.adfs.constant.RegConstants.*;

/**
 * @author fordes123 on 2022/9/19
 */
@Slf4j
public class Util {

    public static boolean startWithAny(String content, String... prefix) {
        return Arrays.stream(prefix).anyMatch(content::startsWith);
    }

    public static boolean startWithAll(String content, String... prefix) {
        return Arrays.stream(prefix).allMatch(content::startsWith);
    }

    public static boolean between(String content, String start, String end) {
        return content.startsWith(start) && content.endsWith(end);
    }

    public static String subBefore(String content, String flag, boolean isLast) {
        int index = isLast? content.lastIndexOf(flag) : content.indexOf(flag);
        if (index > 0) {
            return content.substring(0, index);
        }
        return EMPTY;
    }

    public static String subAfter(String content, String flag, boolean isLast) {
        int index = isLast? content.lastIndexOf(flag) : content.indexOf(flag);
        if (index > 0) {
            return content.substring(index + 1);
        }
        return EMPTY;
    }

    public static String subBetween(String content, String start, String end) {
        int startIndex = content.indexOf(start);
        int endIndex = content.lastIndexOf(end);
        if (startIndex > 0 && endIndex > 0) {
            return content.substring(startIndex + start.length(), endIndex);
        }
        return EMPTY;
    }

    public static List<String> splitIgnoreBlank(String content, String flag) {
        return Arrays.stream(content.split(flag))
                .filter(e -> !e.isBlank())
                .toList();
    }

    public static boolean equalsAny(String content, String... values) {
        return Arrays.asList(values).contains(content);
    }

    public static boolean equalsAnyIgnoreCase(String content, String... values) {
        return Arrays.stream(values).anyMatch(content::equalsIgnoreCase);
    }

    public static Map.Entry<String, String> applyIfHosts(String content) {
        List<String> list = splitIgnoreBlank(content, WHITESPACE);
        if (list.size() == 2) {
            String ip = list.get(0).trim();
            String domain = list.get(1).trim();

            if (PATTERN_IP.matcher(ip).matches() && PATTERN_DOMAIN.matcher(domain).matches()) {
                return Map.entry(ip, domain);
            }
        }
        return null;
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

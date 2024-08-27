package org.fordes.adfs.handler.rule;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.fordes.adfs.constant.Constants.*;
import static org.fordes.adfs.constant.RegConstants.PATTERN_DOMAIN;

/**
 * @author fordes123 on 2024/5/27
 */
@Slf4j
@Component
public final class EasylistHandler extends Handler implements InitializingBean {

    @Override
    public Rule parse(String line) {
        Rule rule = new Rule();
        rule.setOrigin(line);
        rule.setSource(RuleSet.EASYLIST);
        rule.setMode(Rule.Mode.DENY);

        if (line.startsWith(DOUBLE_AT)) {
            rule.setMode(Rule.Mode.ALLOW);
            line = line.substring(2);
        }

        int _head = 0;
        if (line.startsWith(OR)) {
            _head = OR.length();
            rule.getControls().add(Rule.Control.OVERLAY);
        }


        //修饰部分
        int _tail = line.indexOf(CARET);
        if (_tail > 0) {
            String modify = line.substring(_tail + 1);
            if (!modify.isEmpty()) {
                modify = modify.startsWith(DOLLAR) ? modify.substring(1) : modify;
                String[] array = modify.split(COMMA);
                if (!Arrays.stream(array).allMatch(e -> IMPORTANT.equals(e) || DOMAIN.equals(e))) {
                    rule.setType(Rule.Type.UNKNOWN);
                    return rule;
                }

                for (String s : array) {
                    if (s.equals(IMPORTANT)) {
                        rule.getControls().add(Rule.Control.IMPORTANT);
                    }

                    if (s.startsWith(DOMAIN)) {
                        rule.setDest(Util.subAfter(s, DOMAIN + EQUAL, true));
                    }
                }
            }

        }


        //内容部分
        String content = line.substring(_head, _tail > 0 ? _tail : line.length());

        //判断是否为基本或通配规则
        String temp = content.replace(ASTERISK, A);
        if (PATTERN_DOMAIN.matcher(temp).matches()) {
            rule.setType(content.equals(temp) ? Rule.Type.BASIC : Rule.Type.WILDCARD);
            rule.setScope(Rule.Scope.DOMAIN);
            rule.setTarget(content);
            if (Rule.Mode.DENY.equals(rule.getMode())) {
                rule.setDest(LOCAL_V4);
            }
            return rule;
        }

        rule.setType(Rule.Type.UNKNOWN);
        return rule;
    }

    @Override
    public String format(Rule rule) {
        if (Rule.Type.UNKNOWN != rule.getType() && Rule.Mode.REWRITE != rule.getMode()) {

            StringBuilder builder = new StringBuilder();
            StringBuilder tail = new StringBuilder();
            if (rule.getMode() == Rule.Mode.ALLOW) {
                builder.append(DOUBLE_AT);
            }

            //匹配域名及其所有子域名
            if (rule.getControls().contains(Rule.Control.OVERLAY)) {
                builder.append(OR);
            } else {
                if (rule.getType() == Rule.Type.BASIC) {
                    tail.append(DOLLAR).append(DOMAIN).append(EQUAL)
                            .append(Optional.ofNullable(rule.getDest())
                                    .filter(e -> !e.isEmpty()).orElse(rule.getTarget()));
                }
            }

            //匹配目标
            builder.append(rule.getTarget());

            //高优先级
            if (rule.getControls().contains(Rule.Control.IMPORTANT)) {
                if (tail.isEmpty()) {
                    tail.append(DOLLAR);
                }
                tail.append(IMPORTANT);
            }

            builder.append(CARET);
            if (!tail.isEmpty()) {
                builder.append(tail);
            }
            return builder.toString();
        }

        //同源未知规则可直接写出
        if (Rule.Type.UNKNOWN == rule.getType() && RuleSet.EASYLIST == rule.getSource()) {
            return rule.getOrigin();
        }
        return null;
    }

    @Override
    public String commented(String value) {
        return Util.splitIgnoreBlank(value, LF).stream()
                .map(e -> EXCLAMATION + WHITESPACE + e.trim())
                .collect(Collectors.joining(CRLF));
    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.EASYLIST, this);
    }

    @Override
    public boolean isComment(String line) {
        return Util.startWithAny(line, HASH, EXCLAMATION) || Util.between(line, LEFT_BRACKETS, RIGHT_BRACKETS);
    }
}
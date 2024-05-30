package org.fordes.adfs.handler.rule;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.fordes.adfs.constant.Constants.*;
import static org.fordes.adfs.constant.RegConstants.PATTERN_DOMAIN;
import static org.fordes.adfs.constant.RegConstants.PATTERN_IP;

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

        if (line.endsWith(IMPORTANT)) {
            line = line.substring(0, line.length() - IMPORTANT.length());
            rule.setControls(new HashSet<>() {{
                add(Rule.Control.IMPORTANT);
            }});
        }

        //不处理修饰规则
        if (line.contains(DOLLAR)) {
            rule.setType(Rule.Type.UNKNOWN);
            return rule;
        }

        //解析规则控制符
        int startIndex = 0, endIndex = line.length();
        if (line.startsWith(ALLOW_PREFIX)) {
            startIndex = ALLOW_PREFIX.length();
            rule.setMode(Rule.Mode.ALLOW);
        } else {
            if (line.startsWith(OR)) {
                startIndex = OR.length();
            }
            rule.setMode(Rule.Mode.DENY);
        }

        if (line.endsWith(CARET)) {
            Set<Rule.Control> controls = Optional.ofNullable(rule.getControls())
                    .filter(e -> !e.isEmpty()).orElse(new HashSet<>());
            controls.add(Rule.Control.OVERLAY);
            rule.setControls(controls);

            endIndex = line.length() - CARET.length();
        }

        String prue = line.substring(startIndex, endIndex);

        //判断是否是host
        Map.Entry<String, String> entry = Util.applyIfHosts(prue);
        if (entry != null) {
            rule.setDest(entry.getKey());
            rule.setTarget(entry.getValue());
            rule.setType(Rule.Type.BASIC);
            rule.setScope(Rule.Scope.DOMAIN);
            rule.setMode(Util.equalsAny(entry.getKey(), LOCAL_V4, LOCAL_V6) ? Rule.Mode.DENY : Rule.Mode.REWRITE);
            return rule;
        }

        //判断是否是ip
        if (PATTERN_IP.matcher(prue).matches()) {
            rule.setTarget(prue);
            rule.setType(Rule.Type.BASIC);
            rule.setScope(Rule.Scope.HOST);
            rule.setMode(Rule.Mode.DENY);
            return rule;
        }

        //判断是否是domain
        String temp = prue.contains(ASTERISK) ? prue.replace(ASTERISK, EMPTY) : prue;
        if (PATTERN_DOMAIN.matcher(temp).matches()) {
            rule.setType(prue.equals(temp) ? Rule.Type.BASIC : Rule.Type.WILDCARD);
            rule.setScope(Rule.Scope.DOMAIN);
            rule.setTarget(prue);
            if (Rule.Mode.DENY.equals(rule.getMode())) {
                rule.setDest(LOCAL_V4);
            }
            return rule;
        }

        rule.setTarget(prue);
        rule.setScope(prue.contains(SLASH) ? Rule.Scope.PATH : Rule.Scope.DOMAIN);
        rule.setType(prue.contains(ASTERISK) ? Rule.Type.WILDCARD : Rule.Type.UNKNOWN);
        return rule;
    }

    @Override
    public String format(Rule rule) {
        if (Rule.Type.UNKNOWN == rule.getType()) {
            if (RuleSet.EASYLIST == rule.getSource()) {
                return rule.getOrigin();
            }
            return null;
        } else {
            StringBuilder builder = new StringBuilder();
            if (rule.getMode() == Rule.Mode.DENY) {
                builder.append(OR);
            } else if (rule.getMode() == Rule.Mode.ALLOW) {
                builder.append(ALLOW_PREFIX);
            } else {
                return builder.append(rule.getTarget())
                        .append(EMPTY)
                        .append(rule.getDest()).toString();
            }
            builder.append(rule.getTarget());
            Set<Rule.Control> controls = Optional.ofNullable(rule.getControls()).orElse(Set.of());
            if (controls.contains(Rule.Control.OVERLAY)) {
                builder.append(CARET);
            }
            if (controls.contains(Rule.Control.IMPORTANT)) {
                builder.append(IMPORTANT);
            }
            return builder.toString();
        }

    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.EASYLIST, this);
    }

    @Override
    public boolean isComment(String line) {
        return Util.startWithAny(line, HASH, EXCLAMATION)  || Util.between(line, LEFT_BRACKETS, RIGHT_BRACKETS);
    }
}
package org.fordes.adfs.handler.rule;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.constant.Constants;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

import static org.fordes.adfs.constant.Constants.*;

/**
 * @author fordes123 on 2024/5/27
 */
@Slf4j
@Component
public sealed class EasylistHandler extends Handler implements InitializingBean permits DnsHandler {

    @Override
    public Rule parse(String line) {
        Rule rule = new Rule();
        rule.setOrigin(line);
        rule.setSourceType(RuleSet.EASYLIST);
        rule.setMode(Rule.Mode.DENY);

        // @@ 用于例外的标记规则。 如果用户想取消匹配主机名的过滤，请在规则开头添加此标记。
        if (line.startsWith(DOUBLE_AT)) {
            rule.setMode(Rule.Mode.ALLOW);
            line = line.substring(2);
        }

        // ||：匹配主机名的开头，包括任何子域名。 例如，||example.org 匹配 example.org 和 test.example.org，但不匹配 testexample.org。
        if (line.startsWith(DOUBLE_PIPE)) {
            rule.getControls().add(Rule.Control.OVERLAY);
            line = line.substring(2);
        }

        if (line.contains(Symbol.DOLLAR)) {
            int i = line.indexOf(Symbol.DOLLAR);
            var mod = line.substring(i + 1);
            line = line.substring(0, i);

            if (Constants.IMPORTANT.equals(mod.trim())) {
                rule.getControls().add(Rule.Control.IMPORTANT);
            } else if ("all".equals(mod.trim())) {
                rule.getControls().add(Rule.Control.ALL);
            } else {
                rule.setType(Rule.Type.UNKNOWN);
                return rule;
            }
        }

        // ^ 分隔符字符
        // 与浏览器广告拦截不同，主机名中没有什么需要分隔的字符，因此该字符的唯一目的是标记主机名的结尾。
        if (line.endsWith(Constants.Symbol.CARET)) {
            rule.getControls().add(Rule.Control.QUALIFIER);
            line = line.substring(0, line.length() - 1);
        }

        // 正则规则 /regex/
        if (line.startsWith(Constants.Symbol.SLASH) && line.endsWith(Constants.Symbol.SLASH) && line.length() > 2) {
            rule.setType(Rule.Type.REGEX);
            line = line.substring(1, line.length() - 1);
            rule.setTarget(line);
        }

        Rule.Type type = Util.decectBaseRule(line);
        if (type != null) {
            // BASE、WILDCARD
            rule.setType(type);
            rule.setTarget(line);
            rule.setScope(Rule.Scope.DOMAIN);
            if (Rule.Mode.DENY.equals(rule.getMode())) {
                rule.setDest(UNKNOWN_IP);
            }

        } else if (rule.getType() == null) {
            rule.setType(Rule.Type.UNKNOWN);
        }

        // 无法区分UNKNOWN、REGEX 规则的Scope
        rule.setScope(Rule.Scope.DOMAIN);
        return rule;
    }

    @Override
    public String format(Rule rule) {

        //TODO 确认包含 dnsrewrite等控制符规则是否应被收入 easylist

        // 同源未知规则直接返回原始内容
        if (Rule.Type.UNKNOWN == rule.getType() && RuleSet.EASYLIST == rule.getSourceType()) {
            return rule.getOrigin();
        }

        // 未知规则、重写规则 无法转换
        if (Rule.Type.UNKNOWN == rule.getType() || rule.getMode() == Rule.Mode.REWRITE) {
            return null;
        }


        StringBuilder builder = new StringBuilder();

        // 添加模式前缀
        if (rule.getMode() == Rule.Mode.ALLOW) {
            builder.append(DOUBLE_AT);
        }

        Set<Rule.Control> controls = rule.getControls();

        // 添加覆盖标记
        if (controls.contains(Rule.Control.OVERLAY)) {
            builder.append(DOUBLE_PIPE);
        }

        // 添加目标规则
        if (rule.getType() == Rule.Type.REGEX || rule.getType() == Rule.Type.WILDCARD) {
            builder.append(Symbol.SLASH)
                    .append(rule.getTarget())
                    .append(Symbol.SLASH);
        } else {
            builder.append(rule.getTarget());
        }

        // 添加限定符标记
        if (controls.contains(Rule.Control.QUALIFIER)) {
            builder.append(Symbol.CARET);
        }

        // 添加优先级标记
        if (controls.contains(Rule.Control.IMPORTANT)) {
            builder.append(Symbol.DOLLAR).append(IMPORTANT);
        }

        if (controls.contains(Rule.Control.ALL)) {
            int i = builder.indexOf(Symbol.DOLLAR);
            if (i == -1) {
                builder.append(Symbol.DOLLAR).append(ALL);
            } else {
                builder.append(Symbol.COMMA).append(ALL);
            }
        }

        return builder.toString();
    }

    @Override
    public String commented(String value) {
        return Util.splitIgnoreBlank(value, Symbol.LF).stream()
                .map(e -> Symbol.EXCLAMATION + Symbol.WHITESPACE + e.trim())
                .collect(Collectors.joining(Symbol.CRLF));
    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.EASYLIST, this);
    }

    @Override
    public boolean isComment(String line) {
        return Util.startWithAny(line, Symbol.HASH, Symbol.EXCLAMATION) || Util.between(line, Symbol.LEFT_BRACKETS, Symbol.RIGHT_BRACKETS);
    }
}
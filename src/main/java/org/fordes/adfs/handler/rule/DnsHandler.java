package org.fordes.adfs.handler.rule;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.constant.Constants;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.fordes.adfs.constant.Constants.*;

/**
 * dns (AdGuardHome) 规则处理
 * @author fordes123 on 2025/11/25
 */
@Slf4j
@Component
public final class DnsHandler extends EasylistHandler{
    @Override
    public Rule parse(String line) {

        // 尝试以 hosts 格式解析
        Map.Entry<String, String> entry = Util.parseHosts(line);
        if (entry != null) {
            Rule rule = new Rule();
            rule.setOrigin(line);
            rule.setSourceType(RuleSet.HOSTS);
            rule.setTarget(entry.getValue());
            rule.setMode(LOCAL_IPS.contains(entry.getKey()) && !LOCAL_DOMAINS.contains(entry.getValue()) ? Rule.Mode.DENY : Rule.Mode.REWRITE);
            rule.setDest(Rule.Mode.DENY == rule.getMode() ? UNKNOWN_IP : entry.getKey());
            rule.setScope(Rule.Scope.DOMAIN);
            rule.setType(Rule.Type.BASIC);
            return rule;
        }

        Rule rule = super.parse(line);
        rule.setSourceType(RuleSet.DNS);
        return rule;
    }

    @Override
    public String format(Rule rule) {
        // 同源规则直接返回原始内容
        if (Rule.Type.UNKNOWN == rule.getType()) {
            if (RuleSet.DNS == rule.getSourceType()) {
                return rule.getOrigin();
            }
            return null;
        }

        if (rule.getType() != Rule.Type.BASIC && rule.getType() != Rule.Type.WILDCARD) {
            return null;
        }

        // 重写规则直接以hosts格式返回
        if (rule.getMode() == Rule.Mode.REWRITE) {
            if (Rule.Scope.DOMAIN == rule.getScope() && Rule.Type.BASIC == rule.getType()) {
                return Optional.ofNullable(rule.getDest()).orElse(UNKNOWN_IP) + Symbol.TAB + rule.getTarget();
            }
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
        if (rule.getType() == Rule.Type.REGEX) {
            builder.append(Constants.Symbol.SLASH)
                    .append(rule.getTarget())
                    .append(Constants.Symbol.SLASH);
        } else {
            builder.append(rule.getTarget());
        }

        // 添加限定符标记
        if (controls.contains(Rule.Control.QUALIFIER)) {
            builder.append(Constants.Symbol.CARET);
        }

        // 添加重要标记
        if (controls.contains(Rule.Control.IMPORTANT)) {
            builder.append(Constants.Symbol.DOLLAR).append(IMPORTANT);
        }

        return builder.toString();
    }

    @Override
    public String commented(String value) {
        return super.commented(value);
    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.DNS, this);
    }

    @Override
    public boolean isComment(String line) {
        return super.isComment(line);
    }
}
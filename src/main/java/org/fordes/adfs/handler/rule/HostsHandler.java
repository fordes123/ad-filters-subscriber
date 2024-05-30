package org.fordes.adfs.handler.rule;

import lombok.extern.slf4j.Slf4j;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.fordes.adfs.util.Util;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.fordes.adfs.constant.Constants.*;

/**
 * @author fordes123 on 2024/5/27
 */
@Slf4j
@Component
public final class HostsHandler extends Handler implements InitializingBean {

    @Override
    public Rule parse(String line) {
        Map.Entry<String, String> entry = Util.applyIfHosts(line);
        if (entry == null) {
            return null;
        }

        Rule rule = new Rule();
        rule.setSource(RuleSet.HOSTS);
        rule.setOrigin(line);
        rule.setTarget(entry.getValue());
        rule.setDest(entry.getKey());
        rule.setMode(Util.equalsAny(entry.getKey(), LOCAL_V4, LOCAL_V6) ? Rule.Mode.DENY : Rule.Mode.REWRITE);
        rule.setScope(Rule.Scope.DOMAIN);
        rule.setType(Rule.Type.BASIC);
        return rule;
    }

    @Override
    public String format(Rule rule) {
        if (Rule.Scope.DOMAIN == rule.getScope() &&
                Rule.Type.BASIC == rule.getType() &&
                Rule.Mode.ALLOW != rule.getMode()) {
            return rule.getDest() + TAB + rule.getTarget();
        }
        return null;
    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.HOSTS, this);
    }
}
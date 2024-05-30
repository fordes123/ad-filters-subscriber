package org.fordes.adfs.handler.rule;

import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * @author fordes123 on 2024/5/27
 */
@Component
public final class DnsmasqHandler extends Handler implements InitializingBean {

    @Override
    public Rule parse(String line) {
        return null;
    }

    @Override
    public String format(Rule rule) {
        return "";
    }

    @Override
    public void afterPropertiesSet() {
        this.register(RuleSet.DNSMASQ, this);
    }
}
package org.fordes.adfs.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.fordes.adfs.enums.RuleSet;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;
import java.util.Set;

/**
 *
 * @author fordes on 2025/10/29
 */
@ConfigurationProperties(prefix = "application")
public record InputProperties(

        @NotEmpty(message = "the input config is empty")
        Set<Item> input,

        /**
         * @see #input
         */
        @Deprecated
        Map<String, Set<Item>> rule

) implements InitializingBean {


    @Override
    public void afterPropertiesSet() {
        if (rule != null && !rule.isEmpty()) {
            rule.forEach((k, v) -> this.input.addAll(v));
        }
    }

    public record Item(
            @NotBlank
            String name,

            @DefaultValue("EASYLIST")
            RuleSet type,

            @NotBlank
            String path
    ) {

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Item prop) {
                return prop.path.equals(this.path) || prop.name.equals(this.name);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }

}
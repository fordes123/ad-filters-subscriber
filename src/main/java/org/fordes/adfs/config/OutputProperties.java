package org.fordes.adfs.config;

import lombok.Data;
import org.fordes.adfs.constant.Constants;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.Set;

/**
 * 输出配置
 *
 * @author fordes123 on 2022/9/19
 */
@Data
@Component
@ConfigurationProperties(prefix = "application.output")
public class OutputProperties {

    private String fileHeader;
    private String path;
    private Set<OutputFile> files;

    public record OutputFile(String name, RuleSet type, Set<Rule.Type> filter, String desc) {

        public OutputFile(String name, RuleSet type, Set<Rule.Type> filter, String desc) {
            this.name = Optional.ofNullable(name).filter(StringUtils::hasText).orElseThrow(() -> new IllegalArgumentException("application.output.files.name is required"));
            this.type = Optional.ofNullable(type).orElseThrow(() -> new IllegalArgumentException("application.output.files.type is required"));
            this.desc = Optional.ofNullable(desc).filter(StringUtils::hasText).orElse(Constants.EMPTY);
            this.filter = Optional.ofNullable(filter).orElse(Set.of(Rule.Type.values()));
        }

    }

    public void setPath(String path) {
        this.path = Optional.ofNullable(path).filter(StringUtils::hasText).orElse("rule");
    }

    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}

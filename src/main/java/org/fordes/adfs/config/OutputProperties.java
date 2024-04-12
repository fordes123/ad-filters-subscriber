package org.fordes.adfs.config;

import lombok.Data;
import org.fordes.adfs.enums.RuleType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
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
    private Map<String, Set<RuleType>> files;

    public void setFiles(Map<String, Set<RuleType>> files) {
        if (files.isEmpty() || files.values().stream().allMatch(Set::isEmpty)) {
            this.files = Collections.emptyMap();
            return;
        }
        this.files = files;
    }

    public void setPath(String path) {
        this.path = Optional.ofNullable(path).filter(StringUtils::hasText).orElse("rule");
    }

    public boolean isEmpty() {
        return files == null || files.isEmpty() || files.values().stream().allMatch(Set::isEmpty);
    }
}

package org.fordes.adfs.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.fordes.adfs.enums.RuleSet;
import org.fordes.adfs.model.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 *
 * @author fordes on 2025/10/29
 */
@ConfigurationProperties(prefix = "application.output")
public record OutputProperties(
        @DefaultValue("")
        String fileHeader,

        @DefaultValue("rule")
        String path,

        @NotEmpty(message = "the output config is empty")
        Set<@Valid @NotNull Item> files
) {


    public record Item(
            @NotBlank
            String name,

            @NotNull
            RuleSet type,

            @DefaultValue("")
            String desc,

            @DefaultValue("")
            String fileHeader,

            @NotEmpty
            @DefaultValue({})
            Set<Rule.Type> filter,

            @NotEmpty
            @DefaultValue({})
            Set<@NotBlank String> rule

    ) {

    }

}
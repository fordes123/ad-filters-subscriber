package dev.fordes.adfs.config;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.validation.Validated;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties("adfs.config.rules")
public final class RuleProperties {

    @Min(1)
    @Max(1_048_576)
    private int minLength = 1;

    @Min(1)
    @Max(1_048_576)
    private int maxLength = 65_536;

    @NotNull
    private List<String> whitelist = List.of();
}

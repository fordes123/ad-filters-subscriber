package dev.fordes.adfs.config;

import java.time.Duration;

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
@ConfigurationProperties("adfs.config.dns.cache")
public final class DnsCacheProperties {

    @Min(1_024)
    @Max(1_000_000)
    private int maxEntries = 50_000;

    @NotNull
    private Duration maxTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration maxNegativeTtl = Duration.ofMinutes(1);
}

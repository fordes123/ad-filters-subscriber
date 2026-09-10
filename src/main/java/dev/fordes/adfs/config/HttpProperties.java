package dev.fordes.adfs.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.validation.Validated;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties("adfs.config.http")
public final class HttpProperties {

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);

    @Min(0)
    @Max(10)
    private int maxRedirects = 5;

    @Min(0)
    @Max(5)
    private int retries = 2;

    @NotBlank
    @Size(max = 128)
    private String userAgent = "AdFS/2.0";
}

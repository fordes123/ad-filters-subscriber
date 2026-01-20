package org.fordes.adfs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;

import java.nio.charset.Charset;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 *
 *
 * @author Chengfs on 2026/1/20
 */
@ConfigurationProperties(prefix = "application.fetcher")
public record FetcherProperties(

        @DefaultValue({})
        HttpProperty http,

        @DefaultValue({})
        LocalProperty local
) {

    public record LocalProperty(
            @DefaultValue("UTF-8")
            Charset charset,

            @DefaultValue("4KB")
            @DataSizeUnit(DataUnit.KILOBYTES)
            DataSize bufferSize
    ) {
    }

    public record HttpProperty(
            @DefaultValue("UTF-8")
            Charset charset,

            @DefaultValue("10s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration connectTimeout,

            @DefaultValue("30s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration readTimeout,

            @DefaultValue("30s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration writeTimeout,

            @DefaultValue("30s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration responseTimeout,

            @DefaultValue("4KB")
            @DataSizeUnit(DataUnit.KILOBYTES)
            DataSize bufferSize

    ) {
    }
}
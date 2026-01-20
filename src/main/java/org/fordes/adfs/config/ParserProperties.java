package org.fordes.adfs.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

import java.net.InetAddress;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 *
 *
 * @author Chengfs on 2026/1/20
 */
@ConfigurationProperties(prefix = "application.config.parser")
public record ParserProperties(

        @DefaultValue("trace")
        @Pattern(regexp = "^(trace|debug|info|warn|error|fatal)$")
        String logLevel,

        @DefaultValue("6")
        @Min(1)
        @Max(Integer.MAX_VALUE)
        Integer alertLength,

        @DefaultValue("1000")
        @Positive
        @Max(Integer.MAX_VALUE)
        Integer expected,

        @DefaultValue({})
        Set<@NotBlank String> excludes,

        @DefaultValue({})
        DNSProbe dnsProbe
) {


    public record DNSProbe(
            @DefaultValue("false")
            Boolean enable,

            @DefaultValue("1000ms")
            @DurationUnit(ChronoUnit.MILLIS)
            Duration timeout,

            @DefaultValue("600s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration cacheTtlMin,

            @DefaultValue("86400s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration cacheTtlMax,

            @DefaultValue("300s")
            @DurationUnit(ChronoUnit.SECONDS)
            Duration cacheNegativeTtl,

            @Positive
            @DefaultValue("128")
            Integer parallel,

            @DefaultValue({})
            Set<@NotNull DnsProvide> provider

    ) {
    }

    public record DnsProvide(
            @NotNull
            @DefaultValue("1.1.1.1")
            InetAddress host,

            @Min(1)
            @Max(65535)
            @DefaultValue("53")
            Integer port
    ) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DnsProvide that)) return false;
            return Objects.equals(port(), that.port()) && Objects.equals(host(), that.host());
        }

        @Override
        public int hashCode() {
            return Objects.hash(host(), port());
        }
    }
}
package org.fordes.adfs.config;

import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record BuildPlan(
        List<SourceSpec> sources,
        List<OutputSpec> outputs,
        SourceLoadingPolicy sourceLoading,
        ProcessingPolicy processing,
        LoggingPolicy logging
) {

    private static final Duration MAX_DNS_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    private static final int MAX_DNS_CONCURRENCY = 1_024;

    public BuildPlan {
        Objects.requireNonNull(sources, "sources 不能为空");
        Objects.requireNonNull(outputs, "outputs 不能为空");
        Objects.requireNonNull(sourceLoading, "sourceLoading 不能为空");
        Objects.requireNonNull(processing, "processing 不能为空");
        Objects.requireNonNull(logging, "logging 不能为空");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources 不能为空");
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs 不能为空");
        }
        sources = List.copyOf(sources);
        outputs = List.copyOf(outputs);
        requireUniqueSources(sources);
        requireUniqueOutputs(outputs);
        Set<String> sourceIds = sources.stream().map(SourceSpec::id).collect(java.util.stream.Collectors.toSet());
        for (OutputSpec output : outputs) {
            for (String sourceId : output.sources()) {
                if (!sourceIds.contains(sourceId)) {
                    throw new IllegalArgumentException(
                            "output " + output.path() + " 引用了未定义的 source: " + sourceId);
                }
            }
        }
    }

    private static void requireUniqueSources(List<SourceSpec> sources) {
        Set<String> ids = new HashSet<>();
        for (SourceSpec source : sources) {
            if (!ids.add(source.id())) {
                throw new IllegalArgumentException("source id 重复: " + source.id());
            }
        }
    }

    private static void requireUniqueOutputs(List<OutputSpec> outputs) {
        Set<Path> paths = new HashSet<>();
        for (OutputSpec output : outputs) {
            if (!paths.add(output.path())) {
                throw new IllegalArgumentException("output path 重复: " + output.path());
            }
        }
    }

    public record SourceSpec(
            String id,
            String location,
            RuleFormat format,
            DialectProfile dialect,
            ClashDialect clashDialect,
            int priority
    ) {

        public SourceSpec {
            requireText(id, "source.id");
            requireText(location, "source.location");
            Objects.requireNonNull(format, "source.format 不能为空");
            Objects.requireNonNull(dialect, "source.dialect 不能为空");
            Objects.requireNonNull(clashDialect, "source.clashDialect 不能为空");
        }

    }

    public record OutputSpec(
            Path path,
            RuleFormat format,
            DialectProfile dialect,
            ClashDialect clashDialect,
            String description,
            String header,
            Set<String> sources
    ) {

        public OutputSpec {
            Objects.requireNonNull(path, "output.path 不能为空");
            Objects.requireNonNull(format, "output.format 不能为空");
            Objects.requireNonNull(dialect, "output.dialect 不能为空");
            Objects.requireNonNull(clashDialect, "output.clashDialect 不能为空");
            Objects.requireNonNull(description, "output.description 不能为空");
            Objects.requireNonNull(header, "output.header 不能为空");
            Objects.requireNonNull(sources, "output.sources 不能为空");
            path = path.toAbsolutePath().normalize();
            sources = Set.copyOf(sources);
        }

    }

    public record ProcessingPolicy(
            int minRuleLength,
            int maxRuleLength,
            Set<String> excludedDomains,
            boolean allowNarrowing,
            boolean allowBroadening,
            DnsValidationPolicy dnsValidation
    ) {

        public ProcessingPolicy {
            if (minRuleLength < 0 || maxRuleLength < 0) {
                throw new IllegalArgumentException("规则长度限制不能小于 0");
            }
            if (maxRuleLength > 0 && minRuleLength > maxRuleLength) {
                throw new IllegalArgumentException("minRuleLength 不能大于 maxRuleLength");
            }
            Objects.requireNonNull(excludedDomains, "excludedDomains 不能为空");
            Objects.requireNonNull(dnsValidation, "dnsValidation 不能为空");
            excludedDomains = excludedDomains.stream()
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record SourceLoadingPolicy(
            Charset localCharset,
            Charset httpCharset,
            Duration connectTimeout,
            Duration requestTimeout,
            int localBufferSize,
            int httpBufferSize
    ) {

        public SourceLoadingPolicy {
            Objects.requireNonNull(localCharset, "localCharset 不能为空");
            Objects.requireNonNull(httpCharset, "httpCharset 不能为空");
            Objects.requireNonNull(connectTimeout, "connectTimeout 不能为空");
            Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout 必须大于 0");
            }
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout 必须大于 0");
            }
            if (localBufferSize < 1024) {
                throw new IllegalArgumentException("localBufferSize 不能小于 1024");
            }
            if (httpBufferSize < 1024) {
                throw new IllegalArgumentException("httpBufferSize 不能小于 1024");
            }
        }
    }

    public record DnsValidationPolicy(
            boolean enabled,
            Duration timeout,
            int concurrency,
            Optional<DnsServer> server
    ) {

        public DnsValidationPolicy {
            Objects.requireNonNull(timeout, "dns timeout 不能为空");
            Objects.requireNonNull(server, "dns server 不能为空");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("dns timeout 必须大于 0");
            }
            if (timeout.compareTo(MAX_DNS_TIMEOUT) > 0) {
                throw new IllegalArgumentException("dns timeout 不能超过 " + MAX_DNS_TIMEOUT);
            }
            if (concurrency < 1 || concurrency > MAX_DNS_CONCURRENCY) {
                throw new IllegalArgumentException(
                        "dns concurrency 必须位于 1.." + MAX_DNS_CONCURRENCY);
            }
            if (enabled && server.isEmpty()) {
                throw new IllegalArgumentException("DNS 校验启用时必须配置 server");
            }
        }
    }

    public record LoggingPolicy(boolean includeSuccessfulConversions) {
    }

    public record DnsServer(String host, int port) {

        public DnsServer {
            requireText(host, "dns server host");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("dns server port 必须位于 1..65535");
            }
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}

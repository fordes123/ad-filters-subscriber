package org.fordes.adfs.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.RuleProfile;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

    public BuildPlan withOutputDirectory(Path directory) {
        Objects.requireNonNull(directory, "outputDirectory 不能为空");
        Path target = directory.toAbsolutePath().normalize();
        List<OutputSpec> overridden = outputs.stream()
                .map(output -> new OutputSpec(
                        target.resolve(output.path().getFileName()),
                        output.profile(),
                        output.description(),
                        output.header(),
                        output.sources()
                ))
                .toList();
        return new BuildPlan(sources, overridden, sourceLoading, processing, logging);
    }

    public record SourceSpec(
            String id,
            String location,
            RuleProfile profile,
            int priority
    ) {

        public SourceSpec {
            requireText(id, "source.id");
            requireText(location, "source.location");
            Objects.requireNonNull(profile, "source.profile 不能为空");
        }

        public SourceSpec(
                String id,
                String location,
                RuleFormat format,
                DialectProfile dialect,
                ClashDialect clashDialect,
                int priority
        ) {
            this(id, location, RuleProfile.of(format, dialect, clashDialect), priority);
        }

        public RuleFormat format() {
            return profile.format();
        }

        public DialectProfile dialect() {
            return profile instanceof RuleProfile.Adblock adblock
                    ? adblock.dialect()
                    : DialectProfile.ADBLOCK_BASE;
        }

        public ClashDialect clashDialect() {
            return profile instanceof RuleProfile.Clash clash
                    ? clash.dialect()
                    : ClashDialect.CLASSICAL;
        }

    }

    public record OutputSpec(
            Path path,
            RuleProfile profile,
            String description,
            String header,
            Set<String> sources
    ) {

        public OutputSpec {
            Objects.requireNonNull(path, "output.path 不能为空");
            Objects.requireNonNull(profile, "output.profile 不能为空");
            Objects.requireNonNull(description, "output.description 不能为空");
            Objects.requireNonNull(header, "output.header 不能为空");
            Objects.requireNonNull(sources, "output.sources 不能为空");
            path = path.toAbsolutePath().normalize();
            sources = Set.copyOf(sources);
        }

        public OutputSpec(
                Path path,
                RuleFormat format,
                DialectProfile dialect,
                ClashDialect clashDialect,
                String description,
                String header,
                Set<String> sources
        ) {
            this(
                    path,
                    RuleProfile.of(format, dialect, clashDialect),
                    description,
                    header,
                    sources
            );
        }

        public RuleFormat format() {
            return profile.format();
        }

        public DialectProfile dialect() {
            return profile instanceof RuleProfile.Adblock adblock
                    ? adblock.dialect()
                    : DialectProfile.ADBLOCK_BASE;
        }

        public ClashDialect clashDialect() {
            return profile instanceof RuleProfile.Clash clash
                    ? clash.dialect()
                    : ClashDialect.CLASSICAL;
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

        @JsonCreator
        public static ProcessingPolicy fromConfiguration(
                @JsonProperty("min-rule-length") Integer minRuleLength,
                @JsonProperty("max-rule-length") Integer maxRuleLength,
                @JsonProperty("excluded-domains") Set<String> excludedDomains,
                @JsonProperty("allow-narrowing") Boolean allowNarrowing,
                @JsonProperty("allow-broadening") Boolean allowBroadening,
                @JsonProperty("dns-validation") DnsValidationPolicy dnsValidation
        ) {
            return new ProcessingPolicy(
                    minRuleLength == null ? 0 : minRuleLength,
                    maxRuleLength == null ? 0 : maxRuleLength,
                    excludedDomains == null ? Set.of() : excludedDomains,
                    allowNarrowing == null || allowNarrowing,
                    allowBroadening != null && allowBroadening,
                    dnsValidation == null ? DnsValidationPolicy.defaults() : dnsValidation
            );
        }

        public static ProcessingPolicy defaults() {
            return fromConfiguration(null, null, null, null, null, null);
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

        @JsonCreator
        public static SourceLoadingPolicy fromConfiguration(
                @JsonProperty("local-charset") String localCharset,
                @JsonProperty("http-charset") String httpCharset,
                @JsonProperty("buffer-size") Integer bufferSize,
                @JsonProperty("connect-timeout") Integer connectTimeoutMillis,
                @JsonProperty("request-timeout") Integer requestTimeoutMillis
        ) {
            int size = bufferSize == null ? 4 * 1024 : bufferSize;
            return new SourceLoadingPolicy(
                    charset(localCharset, StandardCharsets.UTF_8),
                    charset(httpCharset, StandardCharsets.UTF_8),
                    Duration.ofMillis(connectTimeoutMillis == null ? 10_000 : connectTimeoutMillis),
                    Duration.ofMillis(requestTimeoutMillis == null ? 30_000 : requestTimeoutMillis),
                    size,
                    size
            );
        }

        public static SourceLoadingPolicy defaults() {
            return fromConfiguration(null, null, null, null, null);
        }

        private static Charset charset(String configured, Charset fallback) {
            return Charset.forName(configured == null ? fallback.name() : configured);
        }
    }

    public record DnsValidationPolicy(
            boolean enabled,
            Duration timeout,
            int concurrency,
            List<String> servers
    ) {

        public DnsValidationPolicy {
            Objects.requireNonNull(timeout, "dns timeout 不能为空");
            Objects.requireNonNull(servers, "dns servers 不能为空");
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
            List<String> normalizedServers = new ArrayList<>(servers.size());
            for (String server : servers) {
                Objects.requireNonNull(server, "dns server endpoint 不能为空");
                if (server.isBlank()) {
                    throw new IllegalArgumentException("dns server endpoint 不能为空");
                }
                normalizedServers.add(server.trim());
            }
            if (normalizedServers.size() != new HashSet<>(normalizedServers).size()) {
                throw new IllegalArgumentException("dns server endpoint 不能重复");
            }
            if (enabled && normalizedServers.isEmpty()) {
                throw new IllegalArgumentException("DNS 校验启用时必须配置 servers");
            }
            servers = List.copyOf(normalizedServers);
        }

        @JsonCreator
        public static DnsValidationPolicy fromConfiguration(
                @JsonProperty("enabled") Boolean enabled,
                @JsonProperty("servers") List<String> servers,
                @JsonProperty("timeout") Integer timeoutMillis,
                @JsonProperty("concurrency") Integer concurrency
        ) {
            return new DnsValidationPolicy(
                    enabled != null && enabled,
                    Duration.ofMillis(timeoutMillis == null ? 1_000 : timeoutMillis),
                    concurrency == null ? 128 : concurrency,
                    servers == null ? List.of() : servers
            );
        }

        public static DnsValidationPolicy defaults() {
            return fromConfiguration(null, null, null, null);
        }
    }

    public record LoggingPolicy(LogLevel level) {

        public LoggingPolicy {
            Objects.requireNonNull(level, "logging.level 不能为空");
        }

        public static LoggingPolicy defaults() {
            return new LoggingPolicy(LogLevel.INFO);
        }

        @JsonCreator
        public static LoggingPolicy fromConfiguration(@JsonProperty("level") String configured) {
            String levelName = configured == null ? LogLevel.INFO.name : configured.trim();
            for (LogLevel level : LogLevel.values()) {
                if (level.name.equalsIgnoreCase(levelName)) {
                    return new LoggingPolicy(level);
                }
            }
            throw new IllegalArgumentException("未知日志等级: " + configured);
        }
    }

    public enum LogLevel {
        TRACE("trace"),
        DEBUG("debug"),
        INFO("info"),
        WARN("warn"),
        ERROR("error"),
        OFF("off");

        public final String name;

        LogLevel(String name) {
            this.name = name;
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}

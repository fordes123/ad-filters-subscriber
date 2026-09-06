package dev.fordes.adfs.source;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import jakarta.inject.Singleton;

import lombok.extern.slf4j.Slf4j;

import dev.fordes.adfs.config.EffectiveConfig.HttpConfig;
import dev.fordes.adfs.config.EffectiveConfig;
import dev.fordes.adfs.config.InputSpec.HttpSource;
import dev.fordes.adfs.config.InputSpec;
import dev.fordes.adfs.error.InputException;

@Singleton
public final class HttpSourceReader implements SourceReader {

    @Override
    public boolean supports(InputSpec input) {
        return input.source() instanceof HttpSource;
    }

    @Override
    public SourceSession open(InputSpec input, EffectiveConfig config) {
        HttpSource source = (HttpSource) input.source();
        SourceSessionState state = new SourceSessionState(
                config.inputLimits().maxSize(), config.rules().preprocessor().maxIncludeDepth());
        return new HttpSession(input.name(), source.uri(), config.http(), state);
    }
}

@Slf4j
final class HttpSession implements SourceSession {

    private static final int ERROR_BODY_LIMIT = 4_096;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(250);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final String name;
    private final HttpConfig config;
    private final SourceSessionState state;
    private final URI rootDirectory;
    private final SourceStream root;

    HttpSession(String name, URI uri, HttpConfig config, SourceSessionState state) {
        this.name = name;
        this.config = config;
        this.state = state;
        this.rootDirectory = uri.resolve(".").normalize();
        this.root = open(uri, 0);
    }

    @Override
    public SourceStream root() {
        return root;
    }

    @Override
    public SourceStream openInclude(SourceStream parent, String reference) {
        URI requested;
        try {
            requested = URI.create(reference);
        } catch (IllegalArgumentException exception) {
            throw new InputException("远程 include URI 非法: reference=" + reference, exception);
        }
        if (requested.isAbsolute() || requested.getAuthority() != null) {
            throw new InputException("远程 include 只接受相对 URI: reference=" + reference);
        }
        URI include = parent.location().resolve(reference);
        validateProtocol(include);
        validateInclude(include);
        return open(include, parent.includeDepth() + 1);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && left.getPort() == right.getPort();
    }

    private void validateInclude(URI uri) {
        try {
            String path = new URI(null, null, uri.getPath(), null).normalize().getPath();
            if (!sameOrigin(uri, rootDirectory) || !path.startsWith(rootDirectory.getPath())
                    || path.indexOf('\\') >= 0) {
                throw new InputException("远程 include 越过根来源基址: uri=" + safeUri(uri));
            }
        } catch (URISyntaxException exception) {
            throw new InputException("远程 include 路径非法: uri=" + safeUri(uri), exception);
        }
    }

    private SourceStream open(URI uri, int depth) {
        state.requireDepth(depth, safeUri(uri));
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            HttpAttempt result = request(uri, depth);
            switch (result) {
                case HttpSuccess(URI responseUri, InputStream stream) -> {
                    return state.register(responseUri, safeUri(responseUri), depth, stream);
                }
                case HttpFailure(InputException failure, boolean retryable, Duration retryAfter) -> {
                    log.debug("HTTP 输入读取失败:  {} ({}) --> {}，第 {} 次尝试，最多尝试 {} 次",
                            name, safeUri(uri), failure.getMessage(), attempt + 1, config.retries() + 1);
                    if (!retryable || attempt == config.retries()) {
                        throw failure;
                    }
                    log.debug("HTTP 重试:  {} --> 第 {} 次尝试，最多尝试 {} 次",
                            name, attempt + 2, config.retries() + 1);
                    waitBeforeRetry(retryAfter, attempt, uri);
                }
            }
        }
        throw new IllegalStateException("HTTP 重试循环必须返回或抛出错误");
    }

    private HttpAttempt request(URI initialUri, int depth) {
        URI uri = initialUri;
        for (int redirects = 0; redirects <= config.maxRedirects(); redirects++) {
            validateProtocol(uri);
            if (depth > 0) {
                validateInclude(uri);
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(Math.toIntExact(config.connectTimeout().toMillis()));
                connection.setReadTimeout(Math.toIntExact(config.readTimeout().toMillis()));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", config.userAgent());
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    if (redirects == config.maxRedirects()) {
                        connection.disconnect();
                        return new HttpFailure(new InputException("HTTP 重定向超过上限: input=" + name
                                + ", uri=" + safeUri(initialUri) + ", max-redirects=" + config.maxRedirects()), false,
                                Duration.ZERO);
                    }
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isBlank()) {
                        return new HttpFailure(new InputException("HTTP 重定向缺少 Location: input=" + name
                                + ", uri=" + safeUri(uri) + ", status=" + status), false, Duration.ZERO);
                    }
                    uri = uri.resolve(location);
                    continue;
                }
                if (status >= HttpURLConnection.HTTP_OK && status < HttpURLConnection.HTTP_MULT_CHOICE) {
                    InputStream input = new DisconnectingInputStream(connection.getInputStream(), connection);
                    return new HttpSuccess(uri, input);
                }
                String body = readErrorBody(connection);
                boolean retryable = status == 408 || status == 429 || status >= 500;
                Duration retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"));
                connection.disconnect();
                return new HttpFailure(new InputException("HTTP 输入响应失败: input=" + name
                        + ", uri=" + safeUri(uri) + ", status=" + status + ", body=" + body), retryable, retryAfter);
            } catch (IOException exception) {
                if (connection != null) {
                    connection.disconnect();
                }
                return new HttpFailure(new InputException("HTTP 输入读取失败: input=" + name
                        + ", uri=" + safeUri(uri) + ", cause=" + exception.getClass().getSimpleName()
                        + ": " + exception.getMessage(), exception), true, Duration.ZERO);
            }
        }
        throw new IllegalStateException("重定向循环必须在循环内结束");
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308;
    }

    private static String readErrorBody(HttpURLConnection connection) throws IOException {
        InputStream error = connection.getErrorStream();
        if (error == null) {
            return "";
        }
        try (error; ByteArrayOutputStream output = new ByteArrayOutputStream(ERROR_BODY_LIMIT)) {
            byte[] buffer = new byte[512];
            int remaining = ERROR_BODY_LIMIT;
            while (remaining > 0) {
                int count = error.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toString(StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", " ");
        }
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            return clampRetryAfter(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException _) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return clampRetryAfter(Duration.between(Instant.now(), retryAt));
            } catch (DateTimeParseException _) {
                return Duration.ZERO;
            }
        }
    }

    private static Duration clampRetryAfter(Duration duration) {
        if (duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : duration;
    }

    private static void waitBeforeRetry(Duration retryAfter, int attempt, URI uri) {
        long multiplier = 1L << attempt;
        Duration exponential = INITIAL_RETRY_DELAY.multipliedBy(multiplier);
        Duration delay = retryAfter.isZero() ? exponential : retryAfter;
        if (delay.compareTo(MAX_RETRY_DELAY) > 0 && retryAfter.isZero()) {
            delay = MAX_RETRY_DELAY;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InputException("HTTP 重试等待被中断: uri=" + safeUri(uri), exception);
        }
    }

    private static void validateProtocol(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InputException("HTTP 来源或重定向协议非法: uri=" + safeUri(uri));
        }
    }

    private static String safeUri(URI uri) {
        String authority = uri.getHost();
        if (authority == null) {
            return uri.getScheme() + ":<invalid>";
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + port + uri.getPath();
    }

    @Override
    public void close() {
        state.close();
    }
}

sealed interface HttpAttempt permits HttpFailure, HttpSuccess {
}

record HttpSuccess(URI uri, InputStream stream) implements HttpAttempt {
}

record HttpFailure(InputException failure, boolean retryable, Duration retryAfter) implements HttpAttempt {
}

final class DisconnectingInputStream extends FilterInputStream {

    private final HttpURLConnection connection;

    DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
        super(input);
        this.connection = connection;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            connection.disconnect();
        }
    }
}

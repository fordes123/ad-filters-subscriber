package org.fordes.adfs.source;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.logging.RuleLogContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SourceOpener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceOpener.class);

    private static final int MAX_ATTEMPTS = 3;

    private final BuildPlan.SourceLoadingPolicy policy;
    private final HttpClient httpClient;

    public SourceOpener(BuildPlan.SourceLoadingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(policy.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public OpenedSource open(BuildPlan.SourceSpec source) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source 不能为空");
        if (isHttp(source.location())) {
            return openHttp(source);
        }
        Path path = Path.of(source.location()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("本地规则源不存在或不是普通文件: " + path);
        }
        return new OpenedSource(
                Files.newInputStream(path),
                policy.localCharset(),
                policy.localBufferSize()
        );
    }

    private OpenedSource openHttp(BuildPlan.SourceSpec source)
            throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(source.location());
        } catch (IllegalArgumentException error) {
            throw new IOException("规则源 URL 无效: source=" + source.id(), error);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(policy.requestTimeout())
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        int attempt = 1;
        while (true) {
            IOException attemptError;
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    InputStream body = response.body();
                    String encoding = response.headers()
                            .firstValue("Content-Encoding")
                            .orElse("")
                            .toLowerCase(Locale.ROOT);
                    try {
                        InputStream decoded = encoding.contains("gzip")
                                ? new GZIPInputStream(body)
                                : body;
                        return new OpenedSource(
                                decoded,
                                policy.httpCharset(),
                                policy.httpBufferSize()
                        );
                    } catch (IOException error) {
                        body.close();
                        throw error;
                    }
                }

                response.body().close();
                IOException statusError = new IOException(
                        "HTTP 规则源返回非成功状态: source=" + source.id() + ", status=" + status);
                if (status < 500) {
                    throw new NonRetryableHttpException(statusError.getMessage());
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw statusError;
                }
                attemptError = statusError;
            } catch (IOException error) {
                if (error instanceof NonRetryableHttpException) {
                    throw error;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw error;
                }
                attemptError = error;
            }
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "规则源读取失败, {} --> 远程规则源: 第 {}/{} 次尝试，{} 秒后重试 --> {}: {}",
                        RuleLogContext.source(source),
                        attempt,
                        MAX_ATTEMPTS,
                        attempt,
                        attemptError.getClass().getSimpleName(),
                        attemptError.getMessage()
                );
            }
            Thread.sleep(Duration.ofSeconds(attempt));
            attempt++;
        }
    }

    private static boolean isHttp(String location) {
        String normalized = location.toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    public record OpenedSource(
            InputStream input,
            Charset charset,
            int bufferSize
    ) implements AutoCloseable {

        public OpenedSource {
            Objects.requireNonNull(input, "input 不能为空");
            Objects.requireNonNull(charset, "charset 不能为空");
            if (bufferSize < 1024) {
                throw new IllegalArgumentException("bufferSize 不能小于 1024");
            }
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class NonRetryableHttpException extends IOException {

        private NonRetryableHttpException(String message) {
            super(message);
        }
    }
}

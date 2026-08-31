package org.fordes.adfs.source;

import com.sun.net.httpserver.HttpServer;
import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceOpenerTest {

    @Test
    void readsGzipHttpSourceWithJdkHttpClient() throws Exception {
        byte[] body = gzip("||http.example.com^\n");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/rules.txt", exchange -> {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();

            BuildPlan.SourceLoadingPolicy policy = new BuildPlan.SourceLoadingPolicy(
                    StandardCharsets.UTF_8,
                    StandardCharsets.UTF_8,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2),
                    4096,
                    4096
            );
            BuildPlan.SourceSpec source = new BuildPlan.SourceSpec(
                    "http-source",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/rules.txt",
                    RuleFormat.EASYLIST,
                    DialectProfile.UBO,
                    ClashDialect.CLASSICAL,
                    0
            );

            try (SourceOpener.OpenedSource opened = new SourceOpener(policy).open(source)) {
                assertEquals(
                        "||http.example.com^\n",
                        new String(opened.input().readAllBytes(), StandardCharsets.UTF_8)
                );
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsLocalSourceUsingConfiguredCharset() throws Exception {
        Path sourceFile = Files.createTempFile("adfs-source-", ".txt");
        try {
            Files.writeString(sourceFile, "广告规则", StandardCharsets.UTF_16LE);
            BuildPlan.SourceLoadingPolicy policy = policy(StandardCharsets.UTF_16LE);

            try (SourceOpener.OpenedSource opened = new SourceOpener(policy).open(
                    source(sourceFile.toString()))) {
                assertEquals(StandardCharsets.UTF_16LE, opened.charset());
                assertEquals("广告规则",
                        new String(opened.input().readAllBytes(), opened.charset()));
            }
        } finally {
            Files.deleteIfExists(sourceFile);
        }
    }

    @Test
    void retriesServerErrorsAndDoesNotRetryClientErrors() throws Exception {
        AtomicInteger retryRequests = new AtomicInteger();
        AtomicInteger clientErrorRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/retry", exchange -> {
                int attempt = retryRequests.incrementAndGet();
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/missing", exchange -> {
                clientErrorRequests.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SourceOpener opener = new SourceOpener(policy(StandardCharsets.UTF_8));

            try (SourceOpener.OpenedSource opened = opener.open(source(base + "/retry"))) {
                assertEquals("ok", new String(opened.input().readAllBytes(), StandardCharsets.UTF_8));
            }
            IOException error = assertThrows(
                    IOException.class,
                    () -> opener.open(source(base + "/missing"))
            );

            assertEquals(2, retryRequests.get());
            assertEquals(1, clientErrorRequests.get());
            assertTrue(error.getMessage().contains("status=404"));
        } finally {
            server.stop(0);
        }
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static BuildPlan.SourceLoadingPolicy policy(java.nio.charset.Charset localCharset) {
        return new BuildPlan.SourceLoadingPolicy(
                localCharset,
                StandardCharsets.UTF_8,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4096,
                4096
        );
    }

    private static BuildPlan.SourceSpec source(String location) {
        return new BuildPlan.SourceSpec(
                "source",
                location,
                RuleFormat.EASYLIST,
                DialectProfile.UBO,
                ClashDialect.CLASSICAL,
                0
        );
    }
}

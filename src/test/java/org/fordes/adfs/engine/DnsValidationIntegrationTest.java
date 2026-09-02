package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DnsValidationIntegrationTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void distinguishesExistingAndInvalidResponses() throws Exception {
        try (TestDnsServer server = TestDnsServer.start();
             DnsValidator validator = new DnsValidator(dnsPolicy(server.port()))) {
            assertEquals(
                    DnsValidator.Status.EXISTS,
                    validator.resolve("exists.test-domain.com")
            );
            assertEquals(
                    DnsValidator.Status.INVALID,
                    validator.resolve("missing.test-domain.com")
            );
            assertEquals(
                    DnsValidator.Status.INVALID,
                    validator.resolve("nodata.test-domain.com")
            );
        }
    }

    @Test
    void retriesTruncatedUdpResponseOverTcp() throws Exception {
        try (TruncatingDnsServer server = TruncatingDnsServer.start();
             DnsValidator validator = new DnsValidator(dnsPolicy(server.port()))) {
            assertEquals(
                    DnsValidator.Status.EXISTS,
                    validator.resolve("tcp.test-domain.com")
            );
        }
    }

    @Test
    void removesRulesWithoutAddressDuringBuild() throws Exception {
        Path sourcePath = tempDirectory.resolve("dns-source.txt");
        Path outputPath = tempDirectory.resolve("output").resolve("validated.txt");
        Files.writeString(
                sourcePath,
                "||exists.test-domain.com^\n||missing.test-domain.com^\n"
                        + "||nodata.test-domain.com^\n",
                StandardCharsets.UTF_8
        );

        try (TestDnsServer server = TestDnsServer.start()) {
            BuildPlan plan = new BuildPlan(
                    List.of(new BuildPlan.SourceSpec(
                            "dns-source",
                            sourcePath.toString(),
                            RuleFormat.EASYLIST,
                            DialectProfile.ABP,
                            ClashDialect.CLASSICAL,
                            0
                    )),
                    List.of(new BuildPlan.OutputSpec(
                            outputPath,
                            RuleFormat.EASYLIST,
                            DialectProfile.ABP,
                            ClashDialect.CLASSICAL,
                            "",
                            "",
                            Set.of()
                    )),
                    fetchPolicy(),
                    new BuildPlan.ProcessingPolicy(
                            0, 0, Set.of(), true, false, dnsPolicy(server.port())),
                    BuildPlan.LoggingPolicy.defaults()
            );

            BuildReport report = new BuildEngine().build(plan);
            String output = Files.readString(outputPath, StandardCharsets.UTF_8);

            assertTrue(output.contains("exists.test-domain.com"));
            assertFalse(output.contains("missing.test-domain.com"));
            assertEquals(1, report.sources().getFirst().parsed());
            assertEquals(2, report.sources().getFirst().invalid());
        }
    }

    @Test
    void usesOneGlobalConcurrencyWindowForAllDomains() throws Exception {
        Path sourcePath = tempDirectory.resolve("concurrent-dns-source.txt");
        Path outputPath = tempDirectory.resolve("output").resolve("concurrent-validated.txt");
        StringBuilder rules = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            rules.append("||domain%02d.test-domain.com^%n".formatted(index));
        }
        Files.writeString(sourcePath, rules, StandardCharsets.UTF_8);

        try (BatchedDnsServer server = BatchedDnsServer.start(4)) {
            BuildPlan plan = new BuildPlan(
                    List.of(new BuildPlan.SourceSpec(
                            "dns-concurrency",
                            sourcePath.toString(),
                            RuleFormat.EASYLIST,
                            DialectProfile.ABP,
                            ClashDialect.CLASSICAL,
                            0
                    )),
                    List.of(new BuildPlan.OutputSpec(
                            outputPath,
                            RuleFormat.EASYLIST,
                            DialectProfile.ABP,
                            ClashDialect.CLASSICAL,
                            "",
                            "",
                            Set.of()
                    )),
                    fetchPolicy(),
                    new BuildPlan.ProcessingPolicy(
                            0, 0, Set.of(), true, false, dnsPolicy(server.port(), 4)),
                    BuildPlan.LoggingPolicy.defaults()
            );

            BuildReport report = new BuildEngine().build(plan);

            assertEquals(4, server.maxPending());
            assertEquals(4, report.sources().getFirst().parsed());
            assertEquals(4, Files.readAllLines(outputPath, StandardCharsets.UTF_8).size());
        }
    }

    private static BuildPlan.DnsValidationPolicy dnsPolicy(int port) {
        return dnsPolicy(port, 4);
    }

    private static BuildPlan.DnsValidationPolicy dnsPolicy(int port, int concurrency) {
        return new BuildPlan.DnsValidationPolicy(
                true,
                Duration.ofSeconds(2),
                concurrency,
                List.of("127.0.0.1:" + port)
        );
    }

    private static BuildPlan.SourceLoadingPolicy fetchPolicy() {
        return new BuildPlan.SourceLoadingPolicy(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                4096,
                4096
        );
    }

    private static final class TestDnsServer implements AutoCloseable {

        private final DatagramSocket socket;
        private final Thread thread;

        private TestDnsServer(DatagramSocket socket) {
            this.socket = socket;
            this.thread = Thread.ofVirtual().name("test-dns-server").start(this::serve);
        }

        static TestDnsServer start() throws SocketException {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0));
            socket.setSoTimeout(2_000);
            return new TestDnsServer(socket);
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            byte[] buffer = new byte[512];
            while (!socket.isClosed()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(request);
                    byte[] query = Arrays.copyOf(request.getData(), request.getLength());
                    String domain = domain(query);
                    boolean missing = domain.startsWith("missing.");
                    boolean hasAddress = !missing && !domain.startsWith("nodata.");
                    byte[] response = response(query, missing ? 3 : 0, hasAddress);
                    socket.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                } catch (SocketTimeoutException ignored) {
                    continue;
                } catch (SocketException error) {
                    return;
                } catch (IOException error) {
                    throw new IllegalStateException("测试 DNS 服务失败", error);
                }
            }
        }

        private static String domain(byte[] query) throws IOException {
            StringBuilder domain = new StringBuilder();
            int cursor = 12;
            while (cursor < query.length) {
                int length = Byte.toUnsignedInt(query[cursor++]);
                if (length == 0) {
                    return domain.toString();
                }
                if (cursor + length > query.length) {
                    throw new IOException("测试 DNS 请求被截断");
                }
                if (!domain.isEmpty()) {
                    domain.append('.');
                }
                domain.append(new String(query, cursor, length, StandardCharsets.US_ASCII));
                cursor += length;
            }
            throw new IOException("测试 DNS 请求缺少域名结束标记");
        }

        private static byte[] response(byte[] query, int responseCode) {
            return response(query, responseCode, true);
        }

        private static byte[] response(byte[] query, int responseCode, boolean hasAddress) {
            try {
                Message request = new Message(query);
                Message response = new Message();
                response.getHeader().setID(request.getHeader().getID());
                response.getHeader().setFlag(Flags.QR);
                response.getHeader().setFlag(Flags.RA);
                response.getHeader().setRcode(responseCode);
                Record question = request.getQuestion();
                response.addRecord(question, Section.QUESTION);
                if (responseCode == 0 && hasAddress) {
                    Record answer = question.getType() == Type.A
                            ? new ARecord(
                                    question.getName(),
                                    DClass.IN,
                                    60,
                                    InetAddress.getLoopbackAddress()
                            )
                            : new AAAARecord(
                                    question.getName(),
                                    DClass.IN,
                                    60,
                                    InetAddress.getByName("::1")
                            );
                    response.addRecord(answer, Section.ANSWER);
                }
                return response.toWire();
            } catch (IOException error) {
                throw new IllegalStateException("测试 DNS 响应生成失败", error);
            }
        }

        @Override
        public void close() throws InterruptedException {
            socket.close();
            thread.join(Duration.ofSeconds(2));
        }
    }

    private static final class TruncatingDnsServer implements AutoCloseable {

        private final DatagramSocket udp;
        private final ServerSocket tcp;
        private final Thread udpThread;
        private final Thread tcpThread;

        private TruncatingDnsServer(DatagramSocket udp, ServerSocket tcp) {
            this.udp = udp;
            this.tcp = tcp;
            this.udpThread = Thread.ofVirtual().name("test-dns-udp-truncated").start(this::serveUdp);
            this.tcpThread = Thread.ofVirtual().name("test-dns-tcp").start(this::serveTcp);
        }

        static TruncatingDnsServer start() throws IOException {
            DatagramSocket udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            ServerSocket tcp = new ServerSocket();
            tcp.setReuseAddress(true);
            try {
                tcp.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), udp.getLocalPort()));
                return new TruncatingDnsServer(udp, tcp);
            } catch (IOException error) {
                udp.close();
                tcp.close();
                throw error;
            }
        }

        int port() {
            return udp.getLocalPort();
        }

        private void serveUdp() {
            byte[] buffer = new byte[512];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                udp.receive(request);
                byte[] response = TestDnsServer.response(
                        Arrays.copyOf(request.getData(), request.getLength()), 0);
                response[2] = (byte) (response[2] | 0x02);
                udp.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
            } catch (SocketException ignored) {
                return;
            } catch (IOException error) {
                throw new IllegalStateException("测试 DNS UDP 服务失败", error);
            }
        }

        private void serveTcp() {
            try (Socket connection = tcp.accept()) {
                DataInputStream input = new DataInputStream(connection.getInputStream());
                byte[] query = input.readNBytes(input.readUnsignedShort());
                byte[] response = TestDnsServer.response(query, 0);
                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                output.writeShort(response.length);
                output.write(response);
                output.flush();
            } catch (SocketException ignored) {
                return;
            } catch (IOException error) {
                throw new IllegalStateException("测试 DNS TCP 服务失败", error);
            }
        }

        @Override
        public void close() throws InterruptedException, IOException {
            udp.close();
            tcp.close();
            udpThread.join(Duration.ofSeconds(2));
            tcpThread.join(Duration.ofSeconds(2));
        }
    }

    private static final class BatchedDnsServer implements AutoCloseable {

        private final DatagramSocket socket;
        private final int batchSize;
        private final Thread thread;
        private volatile int maxPending;

        private BatchedDnsServer(DatagramSocket socket, int batchSize) {
            this.socket = socket;
            this.batchSize = batchSize;
            this.thread = Thread.ofVirtual().name("test-dns-batched").start(this::serve);
        }

        static BatchedDnsServer start(int batchSize) throws SocketException {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), 0));
            return new BatchedDnsServer(socket, batchSize);
        }

        int port() {
            return socket.getLocalPort();
        }

        int maxPending() {
            return maxPending;
        }

        private void serve() {
            byte[] buffer = new byte[512];
            List<PendingResponse> pending = new ArrayList<>(batchSize);
            while (!socket.isClosed()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(request);
                    pending.add(new PendingResponse(
                            TestDnsServer.response(
                                    Arrays.copyOf(request.getData(), request.getLength()), 0),
                            request.getSocketAddress()
                    ));
                    maxPending = Math.max(maxPending, pending.size());
                    if (pending.size() == batchSize) {
                        for (PendingResponse response : pending) {
                            socket.send(new DatagramPacket(
                                    response.message(),
                                    response.message().length,
                                    response.destination()
                            ));
                        }
                        pending.clear();
                    }
                } catch (SocketException error) {
                    return;
                } catch (IOException error) {
                    throw new IllegalStateException("测试 DNS 批量服务失败", error);
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            socket.close();
            thread.join(Duration.ofSeconds(2));
        }

        private record PendingResponse(byte[] message, SocketAddress destination) {
        }
    }
}

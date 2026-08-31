package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class DnsValidator implements AutoCloseable {

    private final Duration timeout;
    private final DnsResolver resolver;

    DnsValidator(BuildPlan.DnsValidationPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy 不能为空");
        this.timeout = policy.timeout();
        this.resolver = new DnsResolver(policy.server().orElseThrow(
                () -> new IllegalArgumentException("DNS 校验启用时必须配置 server")));
    }

    boolean exists(String domain) throws IOException, InterruptedException {
        Objects.requireNonNull(domain, "domain 不能为空");
        return resolver.exists(domain, timeout);
    }

    @Override
    public void close() {
        resolver.close();
    }

    private static final class DnsResolver implements AutoCloseable {

        private static final int DNS_HEADER_LENGTH = 12;
        private static final int MAX_DNS_MESSAGE_LENGTH = 0xFFFF;
        private static final int RESPONSE_FLAG = 0x8000;
        private static final int TRUNCATED_FLAG = 0x0200;
        private static final int OPCODE_MASK = 0x7800;
        private static final int RESPONSE_CODE_MASK = 0x000F;
        private static final int RESPONSE_NO_ERROR = 0;
        private static final int RESPONSE_SERVER_FAILURE = 2;
        private static final int RESPONSE_NXDOMAIN = 3;

        private final InetSocketAddress server;
        private final DatagramSocket socket;
        private final ConcurrentHashMap<Integer, PendingQuery> pending = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger();
        private volatile boolean closed;

        private DnsResolver(BuildPlan.DnsServer configuration) throws IOException {
            this.server = resolveServer(configuration);
            this.socket = new DatagramSocket();
            try {
                socket.connect(this.server);
            } catch (IOException | IllegalArgumentException | SecurityException error) {
                socket.close();
                throw new IOException(
                        "DNS server 连接失败: host=" + configuration.host()
                                + ", port=" + configuration.port(),
                        error
                );
            }
            Thread.ofVirtual().name("adfs-dns-receiver").start(this::receiveLoop);
        }

        private boolean exists(String domain, Duration timeout) throws IOException, InterruptedException {
            byte[] question = encodeQuestion(domain);
            PendingRegistration registration = register(domain);
            byte[] request = encodeRequest(registration.id(), question);
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                synchronized (socket) {
                    socket.send(new DatagramPacket(request, request.length));
                }
                DnsResponse response = await(registration.query().result(), deadline, domain, timeout);
                if (response.truncated()) {
                    response = queryTcp(request, registration.id(), domain, deadline);
                }
                return responseExists(response, domain);
            } finally {
                pending.remove(registration.id(), registration.query());
            }
        }

        private PendingRegistration register(String domain) throws IOException {
            if (closed) {
                throw new IOException("DNS resolver 已关闭");
            }
            for (int attempt = 0; attempt <= 0xFFFF; attempt++) {
                int id = nextId.getAndIncrement() & 0xFFFF;
                PendingQuery query = new PendingQuery(domain, new CompletableFuture<>());
                if (pending.putIfAbsent(id, query) == null) {
                    return new PendingRegistration(id, query);
                }
            }
            throw new IOException("DNS 查询 ID 已耗尽");
        }

        private void receiveLoop() {
            byte[] buffer = new byte[MAX_DNS_MESSAGE_LENGTH];
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    acceptUdpResponse(buffer, packet.getLength());
                } catch (SocketException error) {
                    if (!closed) {
                        failResolver(new IOException("DNS UDP socket 读取失败", error));
                    }
                    return;
                } catch (IOException error) {
                    failResolver(new IOException("DNS UDP socket 读取失败", error));
                    return;
                }
            }
        }

        private void acceptUdpResponse(byte[] message, int length) {
            if (length < 2) {
                return;
            }
            int id = unsignedShort(message, 0);
            PendingQuery query = pending.get(id);
            if (query == null) {
                return;
            }
            try {
                DnsResponse response = decodeResponse(message, length, id, query.domain());
                if (response != null) {
                    query.result().complete(response);
                }
            } catch (IOException error) {
                query.result().completeExceptionally(new IOException(
                        "DNS UDP 响应无效: domain=" + query.domain(),
                        error
                ));
            }
        }

        private DnsResponse queryTcp(byte[] request, int id, String domain, long deadline)
                throws IOException {
            try (Socket tcp = new Socket()) {
                tcp.connect(server, remainingMillis(deadline, domain));
                tcp.setSoTimeout(remainingMillis(deadline, domain));
                DataOutputStream output = new DataOutputStream(tcp.getOutputStream());
                output.writeShort(request.length);
                output.write(request);
                output.flush();

                tcp.setSoTimeout(remainingMillis(deadline, domain));
                DataInputStream input = new DataInputStream(tcp.getInputStream());
                int length;
                try {
                    length = input.readUnsignedShort();
                } catch (EOFException error) {
                    throw new IOException("DNS TCP 响应缺少长度字段: domain=" + domain, error);
                }
                if (length < DNS_HEADER_LENGTH) {
                    throw new IOException("DNS TCP 响应长度无效: domain=" + domain + ", length=" + length);
                }
                byte[] responseBytes = new byte[length];
                input.readFully(responseBytes);
                DnsResponse response = decodeResponse(responseBytes, length, id, domain);
                if (response == null) {
                    throw new IOException("DNS TCP 响应与请求不匹配: domain=" + domain);
                }
                if (response.truncated()) {
                    throw new IOException("DNS TCP 响应不应设置 TC: domain=" + domain);
                }
                return response;
            } catch (SocketTimeoutException error) {
                SocketTimeoutException timeout = new SocketTimeoutException(
                        "DNS TCP 查询超时: domain=" + domain);
                timeout.initCause(error);
                throw timeout;
            }
        }

        private static DnsResponse await(
                CompletableFuture<DnsResponse> result,
                long deadline,
                String domain,
                Duration timeout
        ) throws IOException, InterruptedException {
            try {
                return result.get(remainingMillis(deadline, domain), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                SocketTimeoutException timeoutError = new SocketTimeoutException(
                        "DNS UDP 查询超时: domain=" + domain + ", timeout=" + timeout);
                timeoutError.initCause(error);
                throw timeoutError;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof IOException ioError) {
                    throw ioError;
                }
                throw new IOException("DNS 查询失败: domain=" + domain, cause);
            }
        }

        private static boolean responseExists(DnsResponse response, String domain) throws IOException {
            return switch (response.responseCode()) {
                case RESPONSE_NO_ERROR, RESPONSE_SERVER_FAILURE -> true;
                case RESPONSE_NXDOMAIN -> false;
                default -> throw new IOException(
                        "DNS server 返回失败状态: domain=" + domain
                                + ", rcode=" + response.responseCode());
            };
        }

        private static DnsResponse decodeResponse(
                byte[] message,
                int length,
                int expectedId,
                String expectedDomain
        ) throws IOException {
            if (length < DNS_HEADER_LENGTH) {
                throw new IOException("DNS 响应长度小于固定头: length=" + length);
            }
            if (unsignedShort(message, 0) != expectedId) {
                return null;
            }
            int flags = unsignedShort(message, 2);
            if ((flags & RESPONSE_FLAG) == 0 || (flags & OPCODE_MASK) != 0) {
                return null;
            }
            if (unsignedShort(message, 4) != 1) {
                throw new IOException("DNS 响应 question 数量不是 1");
            }
            DecodedName decoded = decodeName(message, length, DNS_HEADER_LENGTH);
            if (!decoded.name().equals(expectedDomain) || decoded.nextOffset() + 4 > length) {
                return null;
            }
            int queryType = unsignedShort(message, decoded.nextOffset());
            int queryClass = unsignedShort(message, decoded.nextOffset() + 2);
            if (queryType != 1 || queryClass != 1) {
                return null;
            }
            return new DnsResponse(
                    flags & RESPONSE_CODE_MASK,
                    (flags & TRUNCATED_FLAG) != 0
            );
        }

        private void failResolver(IOException error) {
            closed = true;
            socket.close();
            pending.values().forEach(query -> query.result().completeExceptionally(error));
        }

        @Override
        public void close() {
            if (!closed) {
                failResolver(new IOException("DNS resolver 已关闭"));
            }
        }

        private static InetSocketAddress resolveServer(BuildPlan.DnsServer server) throws IOException {
            try {
                InetSocketAddress address = new InetSocketAddress(server.host(), server.port());
                if (address.isUnresolved()) {
                    throw new IOException("DNS server 主机无法解析: host=" + server.host());
                }
                return address;
            } catch (IllegalArgumentException | SecurityException error) {
                throw new IOException(
                        "DNS server 地址无效: host=" + server.host() + ", port=" + server.port(),
                        error
                );
            }
        }

        private static int remainingMillis(long deadline, String domain) throws SocketTimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("DNS 查询超时: domain=" + domain);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            if (TimeUnit.MILLISECONDS.toNanos(millis) < remaining) {
                millis++;
            }
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
        }

        private static byte[] encodeQuestion(String domain) throws IOException {
            byte[] ascii = domain.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer output = ByteBuffer.allocate(ascii.length + 6);
            int labelStart = 0;
            for (int index = 0; index <= ascii.length; index++) {
                if (index < ascii.length && ascii[index] != '.') {
                    continue;
                }
                int labelLength = index - labelStart;
                if (labelLength < 1 || labelLength > 63) {
                    throw new IOException("DNS 域名 label 长度无效: domain=" + domain);
                }
                output.put((byte) labelLength);
                output.put(ascii, labelStart, labelLength);
                labelStart = index + 1;
            }
            output.put((byte) 0);
            output.putShort((short) 1);
            output.putShort((short) 1);
            return Arrays.copyOf(output.array(), output.position());
        }

        private static byte[] encodeRequest(int id, byte[] question) {
            ByteBuffer output = ByteBuffer.allocate(DNS_HEADER_LENGTH + question.length);
            output.putShort((short) id);
            output.putShort((short) 0x0100);
            output.putShort((short) 1);
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.putShort((short) 0);
            output.put(question);
            return output.array();
        }

        private static int unsignedShort(byte[] value, int offset) {
            return (Byte.toUnsignedInt(value[offset]) << 8)
                    | Byte.toUnsignedInt(value[offset + 1]);
        }

        private static DecodedName decodeName(byte[] message, int length, int offset) throws IOException {
            StringBuilder name = new StringBuilder();
            int cursor = offset;
            int nextOffset = -1;
            int jumps = 0;
            while (true) {
                if (cursor >= length) {
                    throw new IOException("DNS question name 超出响应长度");
                }
                int labelLength = Byte.toUnsignedInt(message[cursor]);
                if ((labelLength & 0xC0) == 0xC0) {
                    if (cursor + 1 >= length || ++jumps > 128) {
                        throw new IOException("DNS question name 压缩指针无效");
                    }
                    if (nextOffset < 0) {
                        nextOffset = cursor + 2;
                    }
                    cursor = ((labelLength & 0x3F) << 8)
                            | Byte.toUnsignedInt(message[cursor + 1]);
                    continue;
                }
                if ((labelLength & 0xC0) != 0) {
                    throw new IOException("DNS question name label 标记无效");
                }
                cursor++;
                if (labelLength == 0) {
                    return new DecodedName(
                            name.toString().toLowerCase(Locale.ROOT),
                            nextOffset >= 0 ? nextOffset : cursor
                    );
                }
                if (labelLength > 63 || cursor + labelLength > length) {
                    throw new IOException("DNS question name label 长度无效");
                }
                if (!name.isEmpty()) {
                    name.append('.');
                }
                name.append(new String(message, cursor, labelLength, StandardCharsets.US_ASCII));
                cursor += labelLength;
            }
        }

        private record PendingQuery(String domain, CompletableFuture<DnsResponse> result) {
        }

        private record PendingRegistration(int id, PendingQuery query) {
        }

        private record DnsResponse(int responseCode, boolean truncated) {
        }

        private record DecodedName(String name, int nextOffset) {
        }
    }
}

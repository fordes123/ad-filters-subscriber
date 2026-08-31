package org.fordes.adfs.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

final class DnsReferenceStore implements AutoCloseable {

    private static final int MERGE_FAN_IN = 32;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final int RECORD = 1;
    private static final int END = 0;
    private static final Comparator<Reference> ORDER = Comparator
            .comparing(Reference::domain)
            .thenComparingInt(Reference::sourceIndex)
            .thenComparingLong(Reference::ruleOrdinal);

    private final BuildWorkspace workspace;
    private Path path;
    private Writer writer;
    private boolean closed;

    DnsReferenceStore(BuildWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
    }

    void write(Reference reference) throws IOException {
        if (closed) {
            throw new IOException("DNS 引用存储已经关闭");
        }
        if (writer == null) {
            path = workspace.createFile("dns-reference", ".segment");
            writer = new Writer(path);
        }
        writer.write(reference);
    }

    Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (writer != null) {
            writer.close();
        }
    }

    static Path sort(Path input, BuildWorkspace workspace, long memoryBudget) throws IOException {
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(workspace, "workspace 不能为空");
        if (memoryBudget < 1024 * 1024) {
            throw new IllegalArgumentException("memoryBudget 不能小于 1 MiB");
        }

        List<Path> runs = new ArrayList<>();
        List<Reference> chunk = new ArrayList<>();
        long estimated = 0;
        try (Reader reader = new Reader(input)) {
            Reference reference;
            while ((reference = reader.read()) != null) {
                chunk.add(reference);
                estimated += estimateBytes(reference);
                if (estimated >= memoryBudget) {
                    runs.add(writeSortedRun(chunk, workspace));
                    chunk = new ArrayList<>();
                    estimated = 0;
                }
            }
        }
        if (!chunk.isEmpty() || runs.isEmpty()) {
            runs.add(writeSortedRun(chunk, workspace));
        }
        Files.deleteIfExists(input);

        while (runs.size() > 1) {
            List<Path> merged = new ArrayList<>((runs.size() + MERGE_FAN_IN - 1) / MERGE_FAN_IN);
            for (int start = 0; start < runs.size(); start += MERGE_FAN_IN) {
                int end = Math.min(start + MERGE_FAN_IN, runs.size());
                merged.add(mergeRuns(runs.subList(start, end), workspace));
            }
            runs = List.copyOf(merged);
        }
        return runs.getFirst();
    }

    static Reader reader(Path path) throws IOException {
        return new Reader(path);
    }

    static Writer writer(Path path) throws IOException {
        return new Writer(path);
    }

    private static Path writeSortedRun(List<Reference> references, BuildWorkspace workspace)
            throws IOException {
        references.sort(ORDER);
        Path run = workspace.createFile("dns-reference-run", ".segment");
        try (Writer writer = new Writer(run)) {
            for (Reference reference : references) {
                writer.write(reference);
            }
        }
        return run;
    }

    private static Path mergeRuns(List<Path> runs, BuildWorkspace workspace) throws IOException {
        Path merged = workspace.createFile("dns-reference-merged", ".segment");
        List<Cursor> cursors = new ArrayList<>(runs.size());
        PriorityQueue<Cursor> queue = new PriorityQueue<>(
                Comparator.comparing(Cursor::current, ORDER));
        IOException failure = null;
        try (Writer writer = new Writer(merged)) {
            for (Path run : runs) {
                Cursor cursor = Cursor.open(run);
                cursors.add(cursor);
                if (cursor.current() != null) {
                    queue.add(cursor);
                }
            }
            while (!queue.isEmpty()) {
                Cursor cursor = queue.remove();
                writer.write(cursor.current());
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            for (Cursor cursor : cursors) {
                try {
                    cursor.close();
                } catch (IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        if (failure != null) {
            try {
                Files.deleteIfExists(merged);
            } catch (IOException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
            throw failure;
        }
        for (Path run : runs) {
            Files.deleteIfExists(run);
        }
        return merged;
    }

    private static long estimateBytes(Reference reference) {
        return 48L + reference.domain().length() * 2L;
    }

    record Reference(String domain, int sourceIndex, long ruleOrdinal) {

        Reference {
            Objects.requireNonNull(domain, "domain 不能为空");
            if (domain.isBlank()) {
                throw new IllegalArgumentException("domain 不能为空");
            }
            if (sourceIndex < 0 || ruleOrdinal < 0) {
                throw new IllegalArgumentException("DNS 引用位置不能小于 0");
            }
        }
    }

    static final class Reader implements AutoCloseable {

        private final Path path;
        private final DataInputStream input;
        private boolean ended;

        private Reader(Path path) throws IOException {
            this.path = path;
            this.input = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(path), BUFFER_SIZE));
        }

        Reference read() throws IOException {
            if (ended) {
                return null;
            }
            int marker;
            try {
                marker = input.readUnsignedByte();
            } catch (EOFException error) {
                throw new IOException("DNS 引用段缺少结束标记: " + path, error);
            }
            if (marker == END) {
                ended = true;
                return null;
            }
            if (marker != RECORD) {
                throw new IOException("DNS 引用段记录标记无效: path=" + path + ", marker=" + marker);
            }
            return new Reference(readString(input, path), input.readInt(), input.readLong());
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    static final class Writer implements AutoCloseable {

        private final DataOutputStream output;
        private boolean closed;

        private Writer(Path path) throws IOException {
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path), BUFFER_SIZE));
        }

        void write(Reference reference) throws IOException {
            if (closed) {
                throw new IOException("DNS 引用段已经关闭");
            }
            output.writeByte(RECORD);
            writeString(output, reference.domain());
            output.writeInt(reference.sourceIndex());
            output.writeLong(reference.ruleOrdinal());
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            output.writeByte(END);
            output.close();
        }
    }

    private static final class Cursor implements AutoCloseable {

        private final Reader reader;
        private Reference current;

        private Cursor(Reader reader, Reference current) {
            this.reader = reader;
            this.current = current;
        }

        static Cursor open(Path path) throws IOException {
            Reader reader = new Reader(path);
            try {
                return new Cursor(reader, reader.read());
            } catch (IOException error) {
                reader.close();
                throw error;
            }
        }

        Reference current() {
            return current;
        }

        boolean advance() throws IOException {
            current = reader.read();
            return current != null;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, Path path) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("DNS 引用域名长度无效: path=" + path + ", length=" + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("DNS 引用域名被截断: path=" + path + ", expected=" + length
                    + ", actual=" + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

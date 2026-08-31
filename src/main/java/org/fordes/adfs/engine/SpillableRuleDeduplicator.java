package org.fordes.adfs.engine;

import org.fordes.adfs.model.RuleRecord;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

final class SpillableRuleDeduplicator implements AutoCloseable {

    private static final int PARTITION_COUNT = 16;
    private static final int PARTITION_MASK = PARTITION_COUNT - 1;
    private static final int MAX_PARTITION_DEPTH = 8;
    private static final int MERGE_FAN_IN = 32;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final long BASE_HASH_SEED = 0x9E3779B97F4A7C15L;
    private static final int RECORD = 1;
    private static final int END = 0;

    private final BuildWorkspace workspace;
    private final long memoryBudget;
    private final long partitionFileLimit;
    private final LinkedHashMap<String, Candidate> inMemory;

    private PartitionSet partitions;
    private long estimatedMemory;
    private boolean finished;

    SpillableRuleDeduplicator(BuildWorkspace workspace, long memoryBudget) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        if (memoryBudget < 1024 * 1024) {
            throw new IllegalArgumentException("memoryBudget 不能小于 1 MiB");
        }
        this.memoryBudget = memoryBudget;
        this.partitionFileLimit = Math.max(1024 * 1024, memoryBudget / 4);
        this.inMemory = new LinkedHashMap<>();
    }

    void add(long sequence, String content, RuleRecord origin) throws IOException {
        if (finished) {
            throw new IOException("去重器已经完成");
        }
        Candidate candidate = new Candidate(
                sequence,
                Objects.requireNonNull(content, "content 不能为空"),
                Objects.requireNonNull(origin, "origin 不能为空").sourceId(),
                origin.raw()
        );
        if (partitions != null) {
            partitions.write(candidate, 0);
            return;
        }
        if (inMemory.putIfAbsent(content, candidate) != null) {
            return;
        }
        estimatedMemory += estimateBytes(candidate);
        if (estimatedMemory > memoryBudget) {
            spillInMemory();
        }
    }

    Result finish() throws IOException {
        if (finished) {
            throw new IOException("去重器已经完成");
        }
        finished = true;
        if (partitions == null) {
            Path run = workspace.createFile("winner", ".segment");
            try (CandidateWriter writer = new CandidateWriter(run)) {
                for (Candidate candidate : inMemory.values()) {
                    writer.write(candidate);
                }
            }
            long unique = inMemory.size();
            inMemory.clear();
            return new Result(List.of(new WinnerRun(run, unique)), unique);
        }

        partitions.close();
        List<WinnerRun> runs = new ArrayList<>();
        for (Path partition : partitions.paths()) {
            runs.addAll(reduce(partition, 0));
        }
        List<WinnerRun> compactedRuns = compactRuns(runs);
        long unique = compactedRuns.stream().mapToLong(WinnerRun::count).sum();
        return new Result(compactedRuns, unique);
    }

    @Override
    public void close() throws IOException {
        if (partitions != null) {
            partitions.close();
        }
    }

    private void spillInMemory() throws IOException {
        partitions = new PartitionSet(workspace, "candidate");
        for (Candidate candidate : inMemory.values()) {
            partitions.write(candidate, 0);
        }
        inMemory.clear();
        estimatedMemory = 0;
    }

    private List<WinnerRun> reduce(Path partition, int depth) throws IOException {
        if (Files.size(partition) > partitionFileLimit) {
            if (depth >= MAX_PARTITION_DEPTH) {
                throw new IOException(
                        "去重分区在最大递归深度后仍超过内存预算: path=" + partition
                                + ", size=" + Files.size(partition)
                                + ", budget=" + memoryBudget);
            }
            PartitionSet children = new PartitionSet(workspace, "candidate-depth-" + depth);
            try (CandidateReader reader = new CandidateReader(partition)) {
                Candidate candidate;
                while ((candidate = reader.read()) != null) {
                    children.write(candidate, depth + 1);
                }
            }
            children.close();
            Files.deleteIfExists(partition);
            List<WinnerRun> runs = new ArrayList<>();
            for (Path child : children.paths()) {
                runs.addAll(reduce(child, depth + 1));
            }
            return List.copyOf(runs);
        }

        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        try (CandidateReader reader = new CandidateReader(partition)) {
            Candidate candidate;
            while ((candidate = reader.read()) != null) {
                unique.putIfAbsent(candidate.content(), candidate);
            }
        }
        Path run = workspace.createFile("winner", ".segment");
        try (CandidateWriter writer = new CandidateWriter(run)) {
            for (Candidate candidate : unique.values()) {
                writer.write(candidate);
            }
        }
        Files.deleteIfExists(partition);
        return List.of(new WinnerRun(run, unique.size()));
    }

    private List<WinnerRun> compactRuns(List<WinnerRun> sourceRuns) throws IOException {
        List<WinnerRun> runs = List.copyOf(sourceRuns);
        while (runs.size() > MERGE_FAN_IN) {
            List<WinnerRun> merged = new ArrayList<>((runs.size() + MERGE_FAN_IN - 1) / MERGE_FAN_IN);
            for (int start = 0; start < runs.size(); start += MERGE_FAN_IN) {
                int end = Math.min(start + MERGE_FAN_IN, runs.size());
                merged.add(mergeRuns(runs.subList(start, end)));
            }
            runs = List.copyOf(merged);
        }
        return runs;
    }

    private WinnerRun mergeRuns(List<WinnerRun> runs) throws IOException {
        Path merged = workspace.createFile("winner-merged", ".segment");
        long count = runs.stream().mapToLong(WinnerRun::count).sum();
        try (CandidateWriter writer = new CandidateWriter(merged)) {
            new Result(runs, count).forEach(writer::write);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(merged);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        for (WinnerRun run : runs) {
            Files.deleteIfExists(run.path());
        }
        return new WinnerRun(merged, count);
    }

    private static long estimateBytes(Candidate candidate) {
        return 96L
                + candidate.content().length() * 2L
                + candidate.sourceId().length() * 2L
                + candidate.raw().length() * 2L;
    }

    private static long hash(String value, int depth) {
        long result = BASE_HASH_SEED ^ (BASE_HASH_SEED * (depth + 1L));
        for (int index = 0; index < value.length(); index++) {
            result ^= value.charAt(index);
            result *= 0x100000001B3L;
            result = Long.rotateLeft(result, 13);
        }
        result ^= result >>> 33;
        result *= 0xFF51AFD7ED558CCDL;
        result ^= result >>> 33;
        result *= 0xC4CEB9FE1A85EC53L;
        return result ^ result >>> 33;
    }

    record Candidate(long sequence, String content, String sourceId, String raw) {

        Candidate {
            Objects.requireNonNull(content, "content 不能为空");
            Objects.requireNonNull(sourceId, "sourceId 不能为空");
            Objects.requireNonNull(raw, "raw 不能为空");
        }
    }

    record WinnerRun(Path path, long count) {
    }

    @FunctionalInterface
    interface CandidateConsumer {
        void accept(Candidate candidate) throws IOException;
    }

    record Result(List<WinnerRun> runs, long unique) {

        Result {
            runs = List.copyOf(runs);
        }

        void forEach(CandidateConsumer consumer) throws IOException {
            Objects.requireNonNull(consumer, "consumer 不能为空");
            PriorityQueue<RunCursor> queue = new PriorityQueue<>(
                    Comparator.comparingLong(cursor -> cursor.current().sequence()));
            List<RunCursor> cursors = new ArrayList<>(runs.size());
            IOException failure = null;
            try {
                for (WinnerRun run : runs) {
                    RunCursor cursor = RunCursor.open(run.path());
                    cursors.add(cursor);
                    if (cursor.current() != null) {
                        queue.add(cursor);
                    }
                }
                while (!queue.isEmpty()) {
                    RunCursor cursor = queue.remove();
                    consumer.accept(cursor.current());
                    if (cursor.advance()) {
                        queue.add(cursor);
                    }
                }
            } catch (IOException error) {
                failure = error;
            } finally {
                for (RunCursor cursor : cursors) {
                    try {
                        cursor.close();
                    } catch (IOException error) {
                        if (failure != null) {
                            failure.addSuppressed(error);
                        } else {
                            failure = error;
                        }
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class RunCursor implements AutoCloseable {

        private final CandidateReader reader;
        private Candidate current;

        private RunCursor(CandidateReader reader, Candidate current) {
            this.reader = reader;
            this.current = current;
        }

        static RunCursor open(Path path) throws IOException {
            CandidateReader reader = new CandidateReader(path);
            try {
                return new RunCursor(reader, reader.read());
            } catch (IOException error) {
                reader.close();
                throw error;
            }
        }

        Candidate current() {
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

    private static final class PartitionSet implements AutoCloseable {

        private final BuildWorkspace workspace;
        private final String category;
        private final Path[] paths;
        private final CandidateWriter[] writers;
        private boolean closed;

        private PartitionSet(BuildWorkspace workspace, String category) {
            this.workspace = workspace;
            this.category = category;
            this.paths = new Path[PARTITION_COUNT];
            this.writers = new CandidateWriter[PARTITION_COUNT];
        }

        void write(Candidate candidate, int depth) throws IOException {
            if (closed) {
                throw new IOException("去重分区已经关闭");
            }
            int index = (int) hash(candidate.content(), depth) & PARTITION_MASK;
            if (writers[index] == null) {
                paths[index] = workspace.createFile(category, "-" + index + ".segment");
                writers[index] = new CandidateWriter(paths[index]);
            }
            writers[index].write(candidate);
        }

        List<Path> paths() {
            List<Path> result = new ArrayList<>(PARTITION_COUNT);
            for (Path path : paths) {
                if (path != null) {
                    result.add(path);
                }
            }
            return List.copyOf(result);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            for (CandidateWriter writer : writers) {
                if (writer == null) {
                    continue;
                }
                try {
                    writer.close();
                } catch (IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CandidateWriter implements AutoCloseable {

        private final DataOutputStream output;
        private boolean closed;

        private CandidateWriter(Path path) throws IOException {
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path), BUFFER_SIZE));
        }

        void write(Candidate candidate) throws IOException {
            if (closed) {
                throw new IOException("候选规则段已经关闭");
            }
            output.writeByte(RECORD);
            output.writeLong(candidate.sequence());
            writeString(output, candidate.content());
            writeString(output, candidate.sourceId());
            writeString(output, candidate.raw());
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

    private static final class CandidateReader implements AutoCloseable {

        private final Path path;
        private final DataInputStream input;
        private boolean ended;

        private CandidateReader(Path path) throws IOException {
            this.path = path;
            input = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(path), BUFFER_SIZE));
        }

        Candidate read() throws IOException {
            if (ended) {
                return null;
            }
            final int marker;
            try {
                marker = input.readUnsignedByte();
            } catch (EOFException error) {
                throw new IOException("候选规则段缺少结束标记: " + path, error);
            }
            if (marker == END) {
                ended = true;
                return null;
            }
            if (marker != RECORD) {
                throw new IOException("候选规则段包含未知记录标记: path=" + path
                        + ", marker=" + marker);
            }
            long sequence = input.readLong();
            return new Candidate(
                    sequence,
                    readString(input, path),
                    readString(input, path),
                    readString(input, path)
            );
        }

        @Override
        public void close() throws IOException {
            input.close();
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
            throw new IOException("去重记录字符串长度无效: path=" + path + ", length=" + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("去重记录被截断: path=" + path + ", expected=" + length
                    + ", actual=" + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

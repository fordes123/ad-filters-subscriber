package org.fordes.adfs.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

final class BuildWorkspace implements AutoCloseable {

    private final Path root;
    private final AtomicLong sequence;
    private final List<Path> externalFiles;

    private BuildWorkspace(Path root) {
        this.root = root;
        this.sequence = new AtomicLong();
        this.externalFiles = new CopyOnWriteArrayList<>();
    }

    static BuildWorkspace create() throws IOException {
        return new BuildWorkspace(Files.createTempDirectory("adfs-build-"));
    }

    Path createFile(String category, String suffix) throws IOException {
        Objects.requireNonNull(category, "category 不能为空");
        Objects.requireNonNull(suffix, "suffix 不能为空");
        Path directory = root.resolve(category);
        Files.createDirectories(directory);
        return directory.resolve("%016x%s".formatted(sequence.getAndIncrement(), suffix));
    }

    Path createSiblingFile(Path target, String suffix) throws IOException {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(suffix, "suffix 不能为空");
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("输出路径必须包含父目录: " + target);
        }
        Files.createDirectories(parent);
        Path file = Files.createTempFile(parent, ".adfs-", suffix);
        externalFiles.add(file);
        return file;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (Path file : externalFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException error) {
                failure = appendFailure(failure, "无法清理构建临时文件: " + file, error);
            }
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    failure = appendFailure(failure, "无法清理构建工作区: " + root, error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException appendFailure(
            IOException failure,
            String message,
            IOException cause
    ) {
        if (failure == null) {
            return new IOException(message, cause);
        }
        failure.addSuppressed(cause);
        return failure;
    }
}

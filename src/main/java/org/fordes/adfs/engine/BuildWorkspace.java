package org.fordes.adfs.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class BuildWorkspace implements AutoCloseable {

    private final Path root;
    private final AtomicLong sequence;

    private BuildWorkspace(Path root) {
        this.root = root;
        this.sequence = new AtomicLong();
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

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    if (failure == null) {
                        failure = new IOException("无法清理构建工作区: " + root, error);
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

package org.fordes.adfs.tracking;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class ConversionTracker implements AutoCloseable {

    private static final int BUFFER_SIZE = 256 * 1024;

    private final BufferedWriter writer;

    private ConversionTracker(BufferedWriter writer) {
        this.writer = writer;
    }

    public static ConversionTracker open(Path path) throws IOException {
        Objects.requireNonNull(path, "path 不能为空");
        Path normalized = path.toAbsolutePath().normalize();
        return new ConversionTracker(
                new BufferedWriter(
                        new OutputStreamWriter(
                                new BufferedOutputStream(
                                        Files.newOutputStream(
                                                normalized,
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.TRUNCATE_EXISTING,
                                                StandardOpenOption.WRITE
                                        ),
                                        BUFFER_SIZE
                                ),
                                StandardCharsets.UTF_8
                        ),
                        BUFFER_SIZE
                )
        );
    }

    public void success(
            String source,
            String output,
            String inputRule,
            String outputRule
    ) throws IOException {
        write("SUCCESS", source, output, inputRule, outputRule);
    }

    public void failure(
            String source,
            String output,
            String inputRule,
            String reason
    ) throws IOException {
        write("FAILURE", source, output, inputRule, reason);
    }

    private void write(
            String status,
            String source,
            String output,
            String inputRule,
            String result
    ) throws IOException {
        writer.write('[');
        writer.write(status);
        writer.write("][IN: ");
        writeSingleLine(source);
        writer.write("][OUT: ");
        writeSingleLine(output);
        writer.write("] ");
        writeSingleLine(inputRule);
        writer.write(" -> ");
        writeSingleLine(result);
        writer.newLine();
    }

    private void writeSingleLine(String value) throws IOException {
        Objects.requireNonNull(value, "转换记录内容不能为空");
        int segmentStart = 0;
        for (int index = 0; index < value.length(); index++) {
            String escaped = switch (value.charAt(index)) {
                case '\r' -> "\\r";
                case '\n' -> "\\n";
                case '\t' -> "\\t";
                default -> null;
            };
            if (escaped == null) {
                continue;
            }
            if (segmentStart < index) {
                writer.write(value, segmentStart, index - segmentStart);
            }
            writer.write(escaped);
            segmentStart = index + 1;
        }
        if (segmentStart < value.length()) {
            writer.write(value, segmentStart, value.length() - segmentStart);
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}

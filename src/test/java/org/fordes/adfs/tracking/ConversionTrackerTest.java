package org.fordes.adfs.tracking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConversionTrackerTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void writesSuccessFailureAndEscapesControlCharactersOnOneLine() throws Exception {
        Path log = tempDirectory.resolve("conversion.log");
        try (ConversionTracker tracker = ConversionTracker.open(log)) {
            tracker.success("source\nA", "out", "rule\t1", "converted\rvalue");
            tracker.failure("source", "out", "bad\nrule", "unsupported\treason");
        }

        assertEquals(
                """
                [SUCCESS][IN: source\\nA][OUT: out] rule\\t1 -> converted\\rvalue
                [FAILURE][IN: source][OUT: out] bad\\nrule -> unsupported\\treason
                """.replace("\n", System.lineSeparator()),
                Files.readString(log, StandardCharsets.UTF_8)
        );
    }
}

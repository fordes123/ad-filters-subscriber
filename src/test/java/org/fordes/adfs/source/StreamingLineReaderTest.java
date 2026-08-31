package org.fordes.adfs.source;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StreamingLineReaderTest {

    @Test
    void handlesBomCrLfLfAndFinalLineAcrossSmallBuffers() throws Exception {
        byte[] content = concat(
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "one\r\ntwo\nthree".getBytes(StandardCharsets.UTF_8)
        );
        List<String> lines = new ArrayList<>();

        new StreamingLineReader().read(
                new ByteArrayInputStream(content),
                2,
                line -> lines.add(line.materialize())
        );

        assertEquals(List.of("one", "two", "three"), lines);
    }

    @Test
    void growsLineBufferWithoutTruncatingInput() throws Exception {
        String source = "a".repeat(1_024);
        List<String> lines = new ArrayList<>();

        new StreamingLineReader().read(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                7,
                line -> lines.add(line.materialize())
        );

        assertEquals(List.of(source), lines);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

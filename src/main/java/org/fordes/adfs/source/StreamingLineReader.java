package org.fordes.adfs.source;

import org.fordes.adfs.syntax.LineSlice;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

public final class StreamingLineReader {

    private static final int INITIAL_LINE_CAPACITY = 256;

    public void read(InputStream input, int bufferSize, LineConsumer consumer) throws IOException {
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(consumer, "consumer 不能为空");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize 必须大于 0: " + bufferSize);
        }

        byte[] readBuffer = new byte[bufferSize];
        byte[] lineBuffer = new byte[INITIAL_LINE_CAPACITY];
        int lineLength = 0;
        boolean firstLine = true;
        int read;

        while ((read = input.read(readBuffer)) >= 0) {
            for (int index = 0; index < read; index++) {
                byte value = readBuffer[index];
                if (value == '\n') {
                    int contentLength = lineLength > 0 && lineBuffer[lineLength - 1] == '\r'
                            ? lineLength - 1
                            : lineLength;
                    emit(lineBuffer, contentLength, firstLine, consumer);
                    firstLine = false;
                    lineLength = 0;
                    continue;
                }
                if (lineLength == lineBuffer.length) {
                    lineBuffer = grow(lineBuffer);
                }
                lineBuffer[lineLength++] = value;
            }
        }

        if (lineLength > 0) {
            int contentLength = lineBuffer[lineLength - 1] == '\r' ? lineLength - 1 : lineLength;
            emit(lineBuffer, contentLength, firstLine, consumer);
        }
    }

    private static void emit(byte[] lineBuffer, int lineLength, boolean firstLine, LineConsumer consumer)
            throws IOException {
        byte[] stableLine = Arrays.copyOf(lineBuffer, lineLength);
        int start = firstLine && hasUtf8Bom(stableLine) ? 3 : 0;
        consumer.accept(new LineSlice(stableLine, start, stableLine.length));
    }

    private static byte[] grow(byte[] source) {
        if (source.length > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("逻辑行过长，无法继续扩展缓冲区: " + source.length);
        }
        return Arrays.copyOf(source, source.length * 2);
    }

    private static boolean hasUtf8Bom(byte[] value) {
        return value.length >= 3
                && value[0] == (byte) 0xEF
                && value[1] == (byte) 0xBB
                && value[2] == (byte) 0xBF;
    }
}

package org.fordes.adfs.syntax;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 对 UTF-8 输入缓冲区中单个逻辑行的只读视图。
 */
public record LineSlice(byte[] buffer, int start, int end) {

    public LineSlice {
        Objects.requireNonNull(buffer, "buffer 不能为空");
        if (start < 0 || end < start || end > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "无效行区间: start=" + start + ", end=" + end + ", bufferLength=" + buffer.length);
        }
    }

    public static LineSlice fromUtf8(String value) {
        Objects.requireNonNull(value, "value 不能为空");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new LineSlice(bytes, 0, bytes.length);
    }

    public int length() {
        return end - start;
    }

    public byte byteAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException("行内索引越界: index=" + index + ", length=" + length());
        }
        return buffer[start + index];
    }

    public String materialize() {
        return new String(buffer, start, length(), StandardCharsets.UTF_8);
    }

    public String materialize(Span span) {
        Objects.requireNonNull(span, "span 不能为空");
        if (span.end() > length()) {
            throw new IndexOutOfBoundsException(
                    "span 超出逻辑行: spanEnd=" + span.end() + ", lineLength=" + length());
        }
        return new String(buffer, start + span.start(), span.length(), StandardCharsets.UTF_8);
    }
}

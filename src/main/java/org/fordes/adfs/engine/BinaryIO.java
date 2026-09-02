package org.fordes.adfs.engine;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** 构建期临时段的定长度 UTF-8 字符串编解码。 */
final class BinaryIO {

    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;

    private BinaryIO() {
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        Objects.requireNonNull(output, "output 不能为空");
        Objects.requireNonNull(value, "value 不能为空");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("字符串超过临时段限制: length=" + bytes.length);
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInputStream input, Path path, String field) throws IOException {
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(field, "field 不能为空");
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException(field + "长度无效: path=" + path + ", length=" + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException(field + "被截断: path=" + path + ", expected=" + length
                    + ", actual=" + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

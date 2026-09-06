package dev.fordes.adfs.source;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import dev.fordes.adfs.error.InputException;

/** 以严格 UTF-8 解码物理行，并在构造字符串前限制行长。 */
public final class BoundedLineReader implements AutoCloseable {

    private final SourceStream source;
    private final Reader reader;
    private final int maxLineLength;
    private long lineNumber;
    private int pending = -1;

    public BoundedLineReader(SourceStream source, int maxLineLength) {
        this.source = source;
        this.maxLineLength = maxLineLength;
        this.reader = new InputStreamReader(source.input(), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    public Optional<SourceLine> readLine() {
        StringBuilder line = new StringBuilder(Math.min(maxLineLength, 256));
        try {
            int character = takeCharacter();
            if (character < 0) {
                return Optional.empty();
            }
            while (character >= 0 && character != '\n' && character != '\r') {
                if (line.length() == maxLineLength) {
                    throw new InputException("物理行超过字符上限: source=" + source.description()
                            + ", line=" + (lineNumber + 1) + ", max-line-length=" + maxLineLength);
                }
                line.append((char) character);
                character = reader.read();
            }
            if (character == '\r') {
                int following = reader.read();
                if (following != '\n') {
                    pending = following;
                }
            }
            lineNumber++;
            return Optional.of(new SourceLine(source.description(), lineNumber, line.toString()));
        } catch (CharacterCodingException exception) {
            throw new InputException("输入不是合法 UTF-8: source=" + source.description()
                    + ", line=" + (lineNumber + 1), exception);
        } catch (IOException exception) {
            throw new InputException("读取文本输入失败: source=" + source.description()
                    + ", line=" + (lineNumber + 1), exception);
        }
    }

    private int takeCharacter() throws IOException {
        if (pending < 0) {
            return reader.read();
        }
        int character = pending;
        pending = -1;
        return character;
    }

    @Override
    public void close() {
        source.close();
    }
}

package dev.fordes.adfs.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import dev.fordes.adfs.error.InputException;

public final class SourceStream implements AutoCloseable {

    private final URI location;
    private final String description;
    private final int includeDepth;
    private final InputStream input;
    private boolean closed;

    SourceStream(URI location, String description, int includeDepth, InputStream input) {
        this.location = location;
        this.description = description;
        this.includeDepth = includeDepth;
        this.input = input;
    }

    public URI location() {
        return location;
    }

    public String description() {
        return description;
    }

    public int includeDepth() {
        return includeDepth;
    }

    public InputStream input() {
        return input;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            input.close();
        } catch (IOException exception) {
            throw new InputException("关闭输入流失败: source=" + description, exception);
        }
    }
}

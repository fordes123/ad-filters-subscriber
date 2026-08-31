package org.fordes.adfs.source;

import org.fordes.adfs.syntax.LineSlice;

import java.io.IOException;

@FunctionalInterface
public interface LineConsumer {

    void accept(LineSlice line) throws IOException;
}

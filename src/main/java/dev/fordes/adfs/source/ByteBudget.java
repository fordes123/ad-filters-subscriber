package dev.fordes.adfs.source;

import dev.fordes.adfs.error.InputException;

final class ByteBudget {

    private final long maximum;
    private long consumed;

    ByteBudget(long maximum) {
        this.maximum = maximum;
    }

    void consume(int count, String source) {
        consumed += count;
        if (consumed > maximum) {
            throw new InputException("输入及 include 累计大小超过上限: source=" + source + ", max-size=" + maximum);
        }
    }
}

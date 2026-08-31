package org.fordes.adfs.syntax;

/**
 * 逻辑行内左闭右开的字节区间。
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0) {
            throw new IllegalArgumentException("span start 不能小于 0: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("span end 不能小于 start: start=" + start + ", end=" + end);
        }
    }

    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return start == end;
    }
}

package dev.fordes.adfs.rule.dedup;

public final class OutputDeduplicator {

    private final CanonicalTable table = new CanonicalTable();
    private final CanonicalStore store;

    public OutputDeduplicator(CanonicalStore store) {
        this.store = store;
    }

    public boolean add(byte[] outputRecord) {
        return table.add(Murmur3.hash(outputRecord), outputRecord, store);
    }

    public int size() {
        return table.size();
    }
}

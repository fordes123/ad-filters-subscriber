package dev.fordes.adfs.report;

public record DnsMetrics(long checked, long valid, long invalid, long skipped, long merged, long retries, int cacheEntries) {
}

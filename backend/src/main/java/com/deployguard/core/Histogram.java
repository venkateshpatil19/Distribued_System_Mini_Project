package com.deployguard.core;

import java.util.Arrays;

// Fixed-bucket latency histogram. Agents send bucket counts so the controller can merge them
// and compute an exact bucket-level p95 across all agents (averaging per-agent p95s would be wrong).
public final class Histogram {
    public static final long[] BOUNDS_MS = {10, 25, 50, 75, 100, 150, 200, 300, 500, 750, 1000, 2000};

    private final long[] counts = new long[BOUNDS_MS.length + 1];

    public void record(double ms) {
        int i = 0;
        while (i < BOUNDS_MS.length && ms > BOUNDS_MS[i]) i++;
        counts[i]++;
    }

    public void merge(long[] other) {
        if (other == null) return;
        for (int i = 0; i < Math.min(other.length, counts.length); i++) counts[i] += other[i];
    }

    public long total() {
        return Arrays.stream(counts).sum();
    }

    public long[] counts() {
        return counts.clone();
    }

    // Upper bound of the bucket containing the given quantile; -1 when empty.
    public long quantileUpperBoundMs(double q) {
        long total = total();
        if (total == 0) return -1;
        long rank = (long) Math.ceil(q * total);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) return i < BOUNDS_MS.length ? BOUNDS_MS[i] : 5000;
        }
        return 5000;
    }

    public void reset() {
        Arrays.fill(counts, 0);
    }
}

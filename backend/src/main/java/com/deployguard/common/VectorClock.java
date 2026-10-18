package com.deployguard.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Vector clock keyed by node id. Membership is dynamic, so a missing entry counts as zero.
public final class VectorClock {
    public enum Relation { BEFORE, AFTER, EQUAL, CONCURRENT }

    private final String self;
    private final Map<String, Long> v = new HashMap<>();

    public VectorClock(String self) {
        this.self = self;
    }

    public synchronized Map<String, Long> tick() {
        v.merge(self, 1L, Long::sum);
        return snapshotUnlocked();
    }

    // Merge a received vector, then count the receive as a local event.
    public synchronized Map<String, Long> receive(Map<String, Long> remote) {
        if (remote != null) {
            for (Map.Entry<String, Long> e : remote.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                v.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        v.merge(self, 1L, Long::sum);
        return snapshotUnlocked();
    }

    public synchronized Map<String, Long> snapshot() {
        return snapshotUnlocked();
    }

    private Map<String, Long> snapshotUnlocked() {
        return new TreeMap<>(v);
    }

    public static Relation compare(Map<String, Long> a, Map<String, Long> b) {
        Set<String> keys = new HashSet<>();
        if (a != null) keys.addAll(a.keySet());
        if (b != null) keys.addAll(b.keySet());
        boolean aLess = false;
        boolean bLess = false;
        for (String k : keys) {
            long x = a == null ? 0 : a.getOrDefault(k, 0L);
            long y = b == null ? 0 : b.getOrDefault(k, 0L);
            if (x < y) aLess = true;
            if (y < x) bLess = true;
        }
        if (aLess && bLess) return Relation.CONCURRENT;
        if (aLess) return Relation.BEFORE;
        if (bLess) return Relation.AFTER;
        return Relation.EQUAL;
    }
}

package com.deployguard.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Distributed mutual exclusion with expiring leases and fencing tokens, granted by the leader.
// Tokens come from a counter in the replicated state, so they keep increasing across leader changes.
// A holder may stall past expiry (GC pause, partition); protected writes therefore present their token
// and StateStore.checkFence rejects tokens older than the last one accepted.
public final class Leases {
    public record Lease(String name, String holder, long token, long acquiredAtMs, long expiresAtMs) {}

    private final StateStore store;
    private final Map<String, Lease> leases = new LinkedHashMap<>();

    public Leases(StateStore store) {
        this.store = store;
    }

    // Grants the lease if it is free, expired or already held by the same holder. Each grant gets a new, larger token.
    public synchronized Optional<Lease> acquire(String name, String holder, long ttlMs) {
        if (ttlMs < 100 || ttlMs > 600_000) throw new IllegalArgumentException("ttlMs must be 100..600000");
        long now = System.currentTimeMillis();
        Lease cur = leases.get(name);
        if (cur != null && cur.expiresAtMs() > now && !cur.holder().equals(holder)) return Optional.empty();
        long token = store.write(StateStore::nextToken);
        Lease l = new Lease(name, holder, token, now, now + ttlMs);
        leases.put(name, l);
        return Optional.of(l);
    }

    public synchronized boolean release(Lease l) {
        Lease cur = leases.get(l.name());
        if (cur == null || cur.token() != l.token()) return false;
        leases.remove(l.name());
        return true;
    }

    public synchronized Optional<Lease> current(String name) {
        Lease cur = leases.get(name);
        return cur != null && cur.expiresAtMs() > System.currentTimeMillis() ? Optional.of(cur) : Optional.empty();
    }

    public synchronized List<Map<String, Object>> list() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Lease l : leases.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.name());
            m.put("holder", l.holder());
            m.put("token", l.token());
            m.put("acquiredAt", StateStore.iso(l.acquiredAtMs()));
            m.put("expiresAt", StateStore.iso(l.expiresAtMs()));
            m.put("live", l.expiresAtMs() > now);
            out.add(m);
        }
        return out;
    }

    // Leases are leader-local; a new leader starts with none (old ones would have expired anyway).
    public synchronized void clear() {
        leases.clear();
    }
}

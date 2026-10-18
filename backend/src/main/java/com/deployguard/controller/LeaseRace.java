package com.deployguard.controller;

import com.deployguard.common.Retry;
import com.deployguard.rpc.Rpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// Competing lease holders across controller processes, including the classic "paused holder" case:
// the first holder stalls past its lease expiry, a second holder takes over, and the stale holder's
// late write is rejected by the fencing token check. The leader grants leases; contenders on other
// controllers ask it over gRPC.
public final class LeaseRace {
    static final String LEASE = "demo:race";
    static final String RESOURCE = "demo:counter";

    private final Ctx ctx;
    private final PeerClient peers;
    private long counter;

    public LeaseRace(Ctx ctx, PeerClient peers) {
        this.ctx = ctx;
        this.peers = peers;
    }

    // ---- operations executed on the leader (locally or on behalf of a peer) ----

    Map<String, Object> leaderOp(Map<String, Object> a) {
        String op = String.valueOf(a.get("op"));
        String holder = String.valueOf(a.get("holder"));
        switch (op) {
            case "acquire" -> {
                long ttl = ((Number) a.getOrDefault("ttlMs", 1500)).longValue();
                return ctx.leases.acquire(LEASE, holder, ttl)
                        .<Map<String, Object>>map(l -> Map.of("ok", true, "token", l.token()))
                        .orElse(Map.of("ok", false));
            }
            case "write" -> {
                long token = ((Number) a.get("token")).longValue();
                try {
                    long value = ctx.store.write(st -> {
                        StateStore.checkFence(st, RESOURCE, token);
                        return ++counter;
                    });
                    return Map.of("ok", true, "value", value);
                } catch (StateStore.FencingRejected e) {
                    return Map.of("ok", false, "error", e.getMessage());
                }
            }
            case "release" -> {
                long token = ((Number) a.get("token")).longValue();
                ctx.leases.current(LEASE).filter(l -> l.token() == token).ifPresent(ctx.leases::release);
                return Map.of("ok", true);
            }
            default -> throw new ApiServer.ApiError(400, "unknown lease op " + op);
        }
    }

    private Map<String, Object> call(Map<String, Object> args) {
        if (ctx.isLeader()) return leaderOp(args);
        Integer leader = ctx.leaderNumericId();
        if (leader == null) throw new IllegalStateException("no leader");
        Map<String, Object> m = new LinkedHashMap<>(args);
        return peers.command(leader, "lease-op", m, 5000).data();
    }

    // One contender: acquire, optionally stall past expiry, then attempt the protected write.
    public Map<String, Object> contend(String name, long startDelayMs, long pauseMs, long ttlMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("contender", name);
        r.put("node", ctx.id);
        List<String> log = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        String holder = ctx.id + "/" + name;
        Retry.sleepQuietly(startDelayMs);
        try {
            Long token = null;
            while (token == null && System.currentTimeMillis() - t0 < 8000) {
                Map<String, Object> res = call(Map.of("op", "acquire", "holder", holder, "ttlMs", ttlMs));
                if (Boolean.TRUE.equals(res.get("ok"))) token = ((Number) res.get("token")).longValue();
                else Retry.sleepQuietly(100);
            }
            if (token == null) {
                log.add(stamp(t0) + "gave up waiting for the lease");
                r.put("result", "NO_LEASE");
                return r;
            }
            r.put("token", token);
            log.add(stamp(t0) + "acquired lease with fencing token " + token);
            if (pauseMs > 0) {
                log.add(stamp(t0) + "simulated stop-the-world pause for " + pauseMs + " ms (lease TTL " + ttlMs + " ms)");
                Retry.sleepQuietly(pauseMs);
            }
            Map<String, Object> w = call(Map.of("op", "write", "holder", holder, "token", token));
            if (Boolean.TRUE.equals(w.get("ok"))) {
                log.add(stamp(t0) + "write ACCEPTED, counter = " + w.get("value"));
                r.put("result", "ACCEPTED");
            } else {
                log.add(stamp(t0) + "write REJECTED: " + w.get("error"));
                r.put("result", "REJECTED_STALE_TOKEN");
            }
            call(Map.of("op", "release", "holder", holder, "token", token));
        } catch (RuntimeException e) {
            r.put("result", "ERROR: " + e.getMessage());
        } finally {
            r.put("log", log);
        }
        return r;
    }

    private static String stamp(long t0) {
        return "+" + (System.currentTimeMillis() - t0) + "ms ";
    }

    // A local "slow" contender takes the lease first and stalls; every peer controller (and a second
    // local contender) competes for it, each from its own process.
    public Map<String, Object> run(long ttlMs, long pauseMs) {
        List<CompletableFuture<Map<String, Object>>> calls = new ArrayList<>();
        calls.add(CompletableFuture.supplyAsync(() -> contend("slow-holder", 0, pauseMs, ttlMs)));
        calls.add(CompletableFuture.supplyAsync(() -> contend("local-fast", 300, 0, ttlMs)));
        for (int peer : peers.targets().keySet()) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    Rpc.PeerReply rep = peers.command(peer, "lease-race", Map.of("name", "peer-" + peer, "startDelayMs", 300, "ttlMs", ttlMs), 15_000);
                    return rep.data();
                } catch (RuntimeException e) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("contender", "peer-" + peer);
                    m.put("result", "UNREACHABLE: " + e.getMessage());
                    return m;
                }
            }));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> f : calls) results.add(f.join());
        long accepted = results.stream().filter(m -> "ACCEPTED".equals(m.get("result"))).count();
        long rejected = results.stream().filter(m -> "REJECTED_STALE_TOKEN".equals(m.get("result"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contenders", results);
        out.put("accepted", accepted);
        out.put("rejectedStale", rejected);
        out.put("explanation", "Tokens come from one counter on the leader, so they increase with every grant. "
                + "The leader remembers the largest token accepted for the resource and refuses older ones.");
        ctx.events.record("LEASE_RACE", rejected > 0 ? "stale-write-rejected" : "ok", null,
                Map.of("accepted", accepted, "rejectedStale", rejected, "contenders", results.size()));
        return out;
    }
}

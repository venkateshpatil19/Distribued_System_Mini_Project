package com.deployguard.controller;

import com.deployguard.common.Json;
import com.deployguard.common.Log;
import com.deployguard.common.Msg;
import com.deployguard.rpc.Rpc;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Primary-backup replication. The leader sends its whole state to every follower once a second and
// right after changes. Each copy carries the leader's election epoch; a follower rejects copies from an
// older epoch, so a deposed leader (for example one that was partitioned) cannot overwrite newer data.
final class Replicator {
    private final Ctx ctx;
    private final PeerClient peers;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile long lastSentVersion = -1;
    private volatile long lastSentAt;

    Replicator(Ctx ctx, PeerClient peers) {
        this.ctx = ctx;
        this.peers = peers;
    }

    // Leader side, called every 200 ms: push when the state changed, and at least once a second
    // because replies double as the majority check behind Ctx.hasQuorum().
    void tick() {
        if (ctx.election == null || !ctx.election.isLeader() || ctx.isolated()) return;
        long epoch = ctx.election.epoch();
        if (ctx.readyEpoch != epoch) return;
        // Stamp our epoch into the state so a later leader can compare copies.
        if (ctx.store.epoch() != epoch) ctx.store.write(st -> st.epoch = epoch);
        long v = ctx.store.version();
        long now = System.currentTimeMillis();
        if (v == lastSentVersion && now - lastSentAt < 1000) return;
        lastSentVersion = v;
        lastSentAt = now;
        String state = ctx.store.toJson();
        for (int peer : peers.targets().keySet()) {
            pool.submit(() -> {
                try {
                    Rpc.ReplicateResponse r = peers.replicate(peer, new Rpc.ReplicateRequest(ctx.numericId, epoch, v, state));
                    if (r.ok()) {
                        ctx.peerStatus.put(peer, new Ctx.PeerStatus(r.controllerId(), peer, System.currentTimeMillis(),
                                r.epoch(), r.leaderView(), r.version(), r.startedAtMs()));
                    } else {
                        Log.warn("replication_rejected", "peer", peer, "reason", r.reason());
                    }
                } catch (RuntimeException e) {
                    // Unreachable follower: its lastAck ages out and it shows as down.
                }
            });
        }
    }

    // Follower side.
    Rpc.ReplicateResponse onReplicate(Rpc.ReplicateRequest req) {
        long myEpoch = ctx.election == null ? 0 : ctx.election.epoch();
        if (req.epoch() < myEpoch) {
            return new Rpc.ReplicateResponse(false, ctx.id, myEpoch, ctx.leaderNumericId(), ctx.store.version(), ctx.startedAtMs,
                    "stale leader epoch " + req.epoch() + " < " + myEpoch);
        }
        // Someone with a newer or equal epoch leads; make sure our election state agrees.
        ctx.election.onHeartbeat(req.leaderId(), req.epoch());
        List<Map<String, Object>> fresh = ctx.store.replace(req.state());
        // Stream events we have not seen to dashboards connected to this follower.
        for (Map<String, Object> e : fresh) {
            try {
                ctx.sse.broadcast(String.valueOf(e.get("eventId")), "event", toMsg(e));
            } catch (RuntimeException ex) {
                Log.warn("sse_event_skipped", "error", ex.getMessage());
            }
        }
        return new Rpc.ReplicateResponse(true, ctx.id, req.epoch(), ctx.leaderNumericId(), ctx.store.version(), ctx.startedAtMs, null);
    }

    // Before acting as leader, adopt the freshest copy among the peers (highest epoch, then version).
    // Returns false when we must not lead: no majority reachable, or a higher id is alive (Bully rule).
    boolean catchUp() {
        long[] best = {ctx.store.epoch(), ctx.store.version()};
        Integer bestPeer = null;
        int reachable = 0;
        boolean higherAlive = false;
        for (int peer : peers.targets().keySet()) {
            try {
                Map<String, Object> d = peers.command(peer, "state-version", Map.of(), 1500).data();
                reachable++;
                if (peer > ctx.numericId) higherAlive = true;
                long e = ((Number) d.get("epoch")).longValue();
                long v = ((Number) d.get("version")).longValue();
                if (e > best[0] || (e == best[0] && v > best[1])) {
                    best[0] = e;
                    best[1] = v;
                    bestPeer = peer;
                }
            } catch (RuntimeException ignored) {
                // Peer down; skip it.
            }
        }
        if (higherAlive) {
            ctx.election.startElection("higher controller is alive");
            return false;
        }
        if (reachable + 1 <= (peers.targets().size() + 1) / 2) return false;
        if (bestPeer != null) {
            String state = String.valueOf(peers.command(bestPeer, "get-state", Map.of(), 5000).data().get("state"));
            ctx.store.replace(state);
            Log.info("state_adopted_from_peer", "peer", bestPeer, "epoch", best[0], "version", best[1]);
        }
        return true;
    }

    // Converts a stored event row back into the stream message shape the dashboard expects.
    @SuppressWarnings("unchecked")
    static Msg toMsg(Map<String, Object> e) {
        // JSON numbers come back as Integer; the clock needs Long values.
        Map<String, Long> vc = Json.M.convertValue(e.get("vclock"), new TypeReference<Map<String, Long>>() {});
        long wall = java.time.Instant.parse(String.valueOf(e.get("wallTs"))).toEpochMilli();
        return new Msg(String.valueOf(e.get("type")), String.valueOf(e.get("eventId")), String.valueOf(e.get("nodeId")),
                ((Number) e.get("lamport")).longValue(), vc, wall, (Map<String, Object>) e.get("data"));
    }
}

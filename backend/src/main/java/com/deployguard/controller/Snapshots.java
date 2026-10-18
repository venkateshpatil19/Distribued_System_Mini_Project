package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// Chandy-Lamport snapshot (teaching demonstration).
// Channels: leader -> agent is the command queue delivered in heartbeat replies; agent -> leader is the
// agent's ordered stream of Report calls. Both are FIFO, which is what the algorithm needs.
public final class Snapshots {
    private final Ctx ctx;
    private final Map<String, Run> active = new ConcurrentHashMap<>();

    private static final class Run {
        final String id;
        final Set<String> expected;
        final Map<String, Object> initiatorState;
        final Map<String, Object> agentStates = new ConcurrentHashMap<>();
        final Map<String, List<Map<String, Object>>> channels = new ConcurrentHashMap<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Run(String id, Set<String> expected, Map<String, Object> initiatorState) {
            this.id = id;
            this.expected = expected;
            this.initiatorState = initiatorState;
            for (String a : expected) channels.put(a, new ArrayList<>());
        }
    }

    public Snapshots(Ctx ctx) {
        this.ctx = ctx;
    }

    public Map<String, Object> take(long timeoutMs) throws Exception {
        Set<String> expected = new LinkedHashSet<>();
        for (Map<String, Object> a : ctx.store.agents(ctx.fd, false)) {
            if (Boolean.TRUE.equals(a.get("live"))) expected.add((String) a.get("id"));
        }
        String id = "snap-" + System.currentTimeMillis();
        var st = ctx.clock.tick();
        // Step 1: the initiator records its own state before sending any marker.
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("node", ctx.id);
        self.put("lamport", st.lamport());
        self.put("vclock", st.vclock());
        self.put("latestRevision", ctx.store.read(StateStore::latestRevision));
        self.put("activeRollouts", ctx.store.read(s -> s.rollouts.values().stream().filter(Rollouts::active)
                .map(r -> Map.of("id", r.id, "flagKey", r.flagKey, "envKey", r.envKey, "state", r.state, "currentStage", r.currentStage)).toList()));
        self.put("isLeader", ctx.isLeader());
        Run run = new Run(id, expected, self);
        active.put(id, run);
        save(id, "RUNNING", Map.of("expectedAgents", expected), null);
        ctx.events.record("SNAPSHOT_STARTED", "ok", null, Map.of("snapshotId", id, "expectedAgents", expected));
        // Step 2: send a marker on every outgoing channel (each agent's command queue).
        for (String a : expected) ctx.sendControl(a, "SNAPSHOT_MARKER", Map.of("snapshotId", id, "initiator", ctx.id));
        if (expected.isEmpty()) run.done.complete(null);
        try {
            run.done.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Log.warn("snapshot_timeout", "id", id);
        } finally {
            active.remove(id);
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(run.agentStates.keySet());
        String status = missing.isEmpty() ? "COMPLETE" : "PARTIAL";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("initiator", run.initiatorState);
        state.put("agents", run.agentStates);
        state.put("channels", run.channels);
        state.put("missingAgents", missing);
        state.put("assumptions", List.of(
                "Channels are reliable and FIFO: the leader's command queue per agent, and each agent's ordered reports.",
                "Heartbeat timing is not part of the recorded state.",
                "Only the leader (initiator) and the agents are part of the cut."));
        save(id, status, state, System.currentTimeMillis());
        ctx.events.record("SNAPSHOT_COMPLETED", status.equals("COMPLETE") ? "ok" : "partial", null,
                Map.of("snapshotId", id, "agents", run.agentStates.size(), "missing", missing));
        return get(id);
    }

    // Called for every message an agent reports to the leader, in order.
    void onEvent(Msg m) {
        if (active.isEmpty()) return;
        for (Run run : active.values()) {
            if (!run.expected.contains(m.node()) || run.agentStates.containsKey(m.node())) continue;
            if ("SNAPSHOT_MARKER".equals(m.type()) && run.id.equals(m.body().get("snapshotId"))) {
                // Marker closes the channel agent -> initiator; its state travels with the marker.
                Object s = m.body().get("state");
                run.agentStates.put(m.node(), s == null ? Map.of() : s);
                if (run.agentStates.keySet().containsAll(run.expected)) run.done.complete(null);
            } else {
                // In-transit message: sent before the agent saw the marker, received after we recorded our state.
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("type", m.type());
                summary.put("eventId", m.eventId());
                summary.put("lamport", m.lamport());
                run.channels.get(m.node()).add(summary);
            }
        }
    }

    private void save(String id, String status, Map<String, Object> state, Long completedAt) {
        ctx.store.write(st -> {
            Map<String, Object> snap = st.snapshots.computeIfAbsent(id, k -> new LinkedHashMap<>());
            snap.put("id", id);
            snap.put("initiator", ctx.id);
            snap.put("status", status);
            snap.putIfAbsent("startedAt", StateStore.iso(System.currentTimeMillis()));
            snap.put("completedAt", completedAt == null ? null : StateStore.iso(completedAt));
            snap.put("state", state);
            // Keep the 20 most recent snapshots.
            while (st.snapshots.size() > 20) st.snapshots.remove(st.snapshots.keySet().iterator().next());
            return null;
        });
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> s = ctx.store.read(st -> st.snapshots.get(id));
        if (s == null) throw new ApiServer.ApiError(404, "Snapshot not found");
        return s;
    }

    public List<Map<String, Object>> list() {
        return ctx.store.read(st -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : st.snapshots.values()) {
                Map<String, Object> m = new LinkedHashMap<>(s);
                m.remove("state");
                out.add(0, m);
            }
            return out;
        });
    }
}

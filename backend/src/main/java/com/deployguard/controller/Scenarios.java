package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Retry;
import com.deployguard.controller.StateData.RolloutRec;
import com.deployguard.controller.StateData.RunRec;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.RolloutState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

// Reproducible fault experiments. Each run is stored with its parameters and measured results,
// so it can be replayed with the same parameters and compared in the UI.
public final class Scenarios {
    public static final List<String> NAMES = List.of("bad-canary", "healthy-canary", "leader-failover", "agent-partition", "message-delay");

    private final Ctx ctx;
    private final Rollouts rollouts;
    private final PeerClient peers;
    private final Faults faults;

    public Scenarios(Ctx ctx, Rollouts rollouts, PeerClient peers, Faults faults) {
        this.ctx = ctx;
        this.rollouts = rollouts;
        this.peers = peers;
        this.faults = faults;
    }

    public Map<String, Object> start(String scenario, Map<String, Object> params) {
        if (!NAMES.contains(scenario)) throw new ApiServer.ApiError(400, "scenario must be one of " + NAMES);
        Map<String, Object> p = params == null ? Map.of() : params;
        long id = ctx.store.write(st -> {
            long now = System.currentTimeMillis();
            for (RunRec r : st.runs.values()) {
                if ("RUNNING".equals(r.status) && now - r.startedAt < 300_000) {
                    throw new ApiServer.ApiError(409, "Another experiment is still running; wait for it to finish");
                }
            }
            RunRec r = new RunRec();
            r.id = st.nextRunId++;
            r.scenario = scenario;
            r.params = new LinkedHashMap<>(p);
            r.status = "RUNNING";
            r.startedAt = now;
            r.initiatedBy = ctx.id;
            st.runs.put(r.id, r);
            while (st.runs.size() > 50) st.runs.remove(st.runs.keySet().iterator().next());
            return r.id;
        });
        ctx.events.record("EXPERIMENT_STARTED", "ok", null, Map.of("runId", id, "scenario", scenario, "params", p), true);
        Thread.ofVirtual().start(() -> execute(id, scenario, p));
        return Map.of("id", id, "scenario", scenario, "status", "RUNNING");
    }

    private void execute(long id, String scenario, Map<String, Object> p) {
        Map<String, Object> result;
        String status = "COMPLETED";
        try {
            result = switch (scenario) {
                case "bad-canary" -> canary(p, true);
                case "healthy-canary" -> canary(p, false);
                case "leader-failover" -> leaderFailover(p);
                case "agent-partition" -> agentPartition(p);
                default -> messageDelay(p);
            };
        } catch (Exception e) {
            Log.error("experiment_failed", e, "run", id);
            result = Map.of("error", String.valueOf(e.getMessage()));
            status = "FAILED";
        }
        final Map<String, Object> res = result;
        final String st = status;
        // The run record lives in the leader's state. If we lost leadership meanwhile (the failover
        // scenario isolates us on purpose), hand the result to whoever leads now.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (ctx.isLeader()) {
                    finishRun(id, st, res);
                    break;
                }
                Integer leader = ctx.leaderNumericId();
                if (leader != null && leader != ctx.numericId) {
                    peers.command(leader, "run-finish", Map.of("id", id, "status", st, "result", res), 3000);
                    break;
                }
            } catch (RuntimeException e) {
                Log.warn("run_finish_retry", "run", id, "error", e.getMessage());
            }
            Retry.sleepQuietly(500);
        }
        ctx.events.record("EXPERIMENT_FINISHED", st.equals("COMPLETED") ? "ok" : "error", null,
                Map.of("runId", id, "scenario", scenario, "result", res), true);
    }

    void finishRun(long id, String status, Map<String, Object> result) {
        ctx.store.write(s -> {
            RunRec r = s.runs.get(id);
            if (r != null) {
                r.status = status;
                r.finishedAt = System.currentTimeMillis();
                r.result = new LinkedHashMap<>(result);
            }
            return null;
        });
    }

    private static int intp(Map<String, Object> p, String k, int def) {
        return p.get(k) instanceof Number n ? n.intValue() : def;
    }

    private static double dblp(Map<String, Object> p, String k, double def) {
        return p.get(k) instanceof Number n ? n.doubleValue() : def;
    }

    private static String strp(Map<String, Object> p, String k, String def) {
        Object v = p.get(k);
        return v == null ? def : v.toString();
    }

    private static long waitFor(BooleanSupplier cond, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (cond.getAsBoolean()) return System.currentTimeMillis() - t0;
            Retry.sleepQuietly(200);
        }
        return -1;
    }

    private void ensureTraffic() {
        boolean on = ctx.store.read(st -> Boolean.TRUE.equals(st.traffic.get("enabled")));
        if (!on) {
            ctx.store.write(st -> st.traffic = new LinkedHashMap<>(Map.of("enabled", true, "rps", 40)));
            ctx.sendControl(null, "TRAFFIC", Map.of("enabled", true, "rps", 40));
        }
    }

    // Starts a staged rollout; the bad variant first injects unhealthy behaviour into the new code path.
    private Map<String, Object> canary(Map<String, Object> p, boolean bad) {
        String flag = strp(p, "flag", "new-checkout");
        String env = strp(p, "env", "production");
        int observe = intp(p, "observeSeconds", bad ? 15 : 8);
        ensureTraffic();
        Optional<Long> active = ctx.store.read(st -> st.rollouts.values().stream()
                .filter(r -> r.flagKey.equals(flag) && r.envKey.equals(env) && Rollouts.active(r)).map(r -> r.id).findFirst());
        active.ifPresent(a -> rollouts.act(a, RolloutState.Action.ABORT, ctx.id + " (experiment)", "experiment reset"));
        resetFlag(flag, env);
        if (bad) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "unhealthy");
            f.put("flag", flag);
            f.put("errorRate", dblp(p, "errorRate", 0.3));
            f.put("extraLatencyMs", intp(p, "extraLatencyMs", 0));
            f.put("seconds", 120);
            ctx.sendControl(null, "FAULT", f);
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = rollouts.create(new Rollouts.CreateRequest(null, flag, env, List.of(10, 50, 100), observe,
                0.05, 400L, 30L, "ROLLBACK", true, null), ctx.id + " (experiment)");
        long rid = ((Number) r.get("id")).longValue();
        long waited = waitFor(() -> ctx.store.read(st -> RolloutState.valueOf(st.rollouts.get(rid).state).isTerminal()), 180_000);
        if (bad) ctx.sendControl(null, "FAULT", Map.of("type", "clear"));
        RolloutRec fin = ctx.store.read(st -> st.rollouts.get(rid));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rolloutId", rid);
        out.put("finalState", fin.state);
        out.put("totalMs", waited < 0 ? null : System.currentTimeMillis() - t0);
        out.put("rollbackReason", fin.rollbackReason);
        if (fin.breachDetectedAt != null) {
            out.put("detectMs", fin.breachDetectedAt - fin.createdAt);
            if (fin.rollbackRevision != null) {
                long rev = fin.rollbackRevision;
                long conv = waitFor(() -> converged(env, rev), 30_000);
                out.put("rollbackConvergedMs", conv < 0 ? null : System.currentTimeMillis() - fin.breachDetectedAt);
                out.put("rollbackRevision", rev);
            }
        }
        out.put("stagesReached", fin.currentStage + 1);
        out.put("simulatedTraffic", true);
        return out;
    }

    private void resetFlag(String flag, String env) {
        String res = StateStore.resource(flag, env);
        Leases.Lease l = ctx.acquireWithin(res, ctx.holder("experiment-reset"), 10_000, 5_000)
                .orElseThrow(() -> new IllegalStateException("could not lock " + res));
        try {
            ctx.store.write(st -> {
                FlagEnvConfig cur = StateStore.envConfig(st, flag, env);
                return StateStore.writeConfig(st, flag, env, new FlagEnvConfig(false, cur.rules(), 0, cur.onValue(), cur.offValue()),
                        "experiment reset", ctx.id + " (experiment)", l.token());
            });
        } finally {
            ctx.releaseQuietly(l);
        }
    }

    private boolean converged(String env, long revision) {
        int[] c = rollouts.convergence(env, revision);
        return c[1] > 0 && c[0] == c[1];
    }

    private long countEvents(String type, long sinceMs, String node, boolean sameNode) {
        String since = StateStore.iso(sinceMs);
        return ctx.store.events(1000, type, null).stream()
                .filter(e -> String.valueOf(e.get("recordedAt")).compareTo(since) > 0)
                .filter(e -> sameNode == node.equals(e.get("nodeId"))).count();
    }

    // Isolates the current leader from its peers; measures how long the others take to elect a new one.
    private Map<String, Object> leaderFailover(Map<String, Object> p) {
        int seconds = intp(p, "isolateSeconds", 12);
        Integer leader = ctx.leaderNumericId();
        if (leader == null) throw new IllegalStateException("no leader known");
        String leaderName = "controller-" + leader;
        long t0 = System.currentTimeMillis();
        // This code runs on the leader, so isolating "the leader" means isolating ourselves. The scenario
        // keeps running locally and reads the results from the replicated event log afterwards.
        if (leader == ctx.numericId) faults.controllerFault(Map.of("action", "isolate", "seconds", seconds));
        else peers.command(leader, "fault", Map.of("action", "isolate", "seconds", seconds), 5000);
        long electedMs = waitFor(() -> countEvents("LEADER_ELECTED", t0, leaderName, false) > 0 || otherLeaderSeen(leader), seconds * 1000L + 10_000);
        long restoredMs = waitFor(() -> countEvents("LEADER_ELECTED", t0, leaderName, true) > 0, seconds * 1000L + 20_000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isolatedLeader", leaderName);
        out.put("isolateSeconds", seconds);
        out.put("newLeaderElectedMs", electedMs < 0 ? null : electedMs);
        out.put("originalLeaderReturnedMs", restoredMs < 0 ? null : restoredMs + Math.max(0, electedMs));
        out.put("note", "Bully: once the partition heals, the highest id wins again");
        return out;
    }

    // While we are cut off our own event log does not see the new leader's events; ask the peers instead.
    private boolean otherLeaderSeen(int oldLeader) {
        for (int peer : peers.targets().keySet()) {
            try {
                Object l = peers.command(peer, "status", Map.of(), 1000).data().get("leader");
                if (l != null && !String.valueOf(oldLeader).equals(String.valueOf(l)) && !"null".equals(String.valueOf(l))) return true;
            } catch (RuntimeException ignored) {
                // Unreachable while isolated; try again on the next poll.
            }
        }
        return false;
    }

    // Partitions one agent: measures failure-detection delay and recovery after the partition heals.
    private Map<String, Object> agentPartition(Map<String, Object> p) {
        int seconds = intp(p, "seconds", 10);
        List<Map<String, Object>> agents = ctx.store.agents(ctx.fd, false).stream().filter(a -> Boolean.TRUE.equals(a.get("live"))).toList();
        if (agents.isEmpty()) throw new IllegalStateException("no live agents");
        String agent = strp(p, "agentId", (String) agents.get(0).get("id"));
        long t0 = System.currentTimeMillis();
        ctx.sendControl(agent, "FAULT", Map.of("type", "partition", "seconds", seconds));
        long suspected = waitFor(() -> health(agent).filter(h -> h.equals("SUSPECTED") || h.equals("DISCONNECTED")).isPresent(), seconds * 1000L + 5000);
        long recovered = waitFor(() -> health(agent).filter(h -> h.equals("HEALTHY") || h.equals("RECOVERING")).isPresent(), seconds * 1000L + 20_000);
        long rev = ctx.store.read(StateStore::latestRevision);
        long conv = waitFor(() -> appliedAtLeast(agent, rev), 20_000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", agent);
        out.put("partitionSeconds", seconds);
        out.put("suspectedAfterMs", suspected < 0 ? null : suspected);
        out.put("recoveredAfterMs", recovered < 0 ? null : recovered + Math.max(0, suspected));
        out.put("convergedAfterRecoveryMs", conv < 0 ? null : conv);
        out.put("totalMs", System.currentTimeMillis() - t0);
        out.put("suspectThresholdMs", ctx.fd.suspectAfterMs());
        return out;
    }

    // Pushes one config change normally and once while every agent delays handling it; compares convergence.
    private Map<String, Object> messageDelay(Map<String, Object> p) {
        int delay = intp(p, "delayMs", 2500);
        String flag = strp(p, "flag", "promo-banner");
        String env = strp(p, "env", "production");
        long baseline = propagate(flag, env);
        ctx.sendControl(null, "FAULT", Map.of("type", "delay", "delayMs", delay, "seconds", 60));
        // Give the fault one heartbeat to reach the agents.
        Retry.sleepQuietly(1500);
        long delayed = propagate(flag, env);
        ctx.sendControl(null, "FAULT", Map.of("type", "clear"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("injectedDelayMs", delay);
        out.put("baselineConvergenceMs", baseline);
        out.put("delayedConvergenceMs", delayed);
        return out;
    }

    private long propagate(String flag, String env) {
        String res = StateStore.resource(flag, env);
        Leases.Lease l = ctx.acquireWithin(res, ctx.holder("experiment-probe"), 10_000, 5_000)
                .orElseThrow(() -> new IllegalStateException("could not lock " + res));
        long rev;
        try {
            rev = ctx.store.write(st -> StateStore.writeConfig(st, flag, env, StateStore.envConfig(st, flag, env),
                    "experiment propagation probe", ctx.id + " (experiment)", l.token()).revision());
        } finally {
            ctx.releaseQuietly(l);
        }
        long t0 = System.currentTimeMillis();
        long ms = waitFor(() -> converged(env, rev), 30_000);
        return ms < 0 ? -1 : System.currentTimeMillis() - t0;
    }

    private Optional<String> health(String agent) {
        return ctx.store.agents(ctx.fd, true).stream().filter(a -> agent.equals(a.get("id"))).map(a -> (String) a.get("health")).findFirst();
    }

    private boolean appliedAtLeast(String agent, long rev) {
        return ctx.store.agents(ctx.fd, true).stream().anyMatch(a -> agent.equals(a.get("id")) && ((Number) a.get("appliedRevision")).longValue() >= rev);
    }

    public List<Map<String, Object>> runs(int limit) {
        return ctx.store.read(st -> {
            List<Map<String, Object>> out = new ArrayList<>();
            List<RunRec> all = new ArrayList<>(st.runs.values());
            for (int i = all.size() - 1; i >= 0 && out.size() < Math.max(1, Math.min(limit, 100)); i--) out.add(StateStore.view(all.get(i)));
            return out;
        });
    }

    public Map<String, Object> replay(long runId) {
        RunRec r = ctx.store.read(st -> st.runs.get(runId));
        if (r == null) throw new ApiServer.ApiError(404, "Experiment run not found");
        return start(r.scenario, r.params);
    }
}

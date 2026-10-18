package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.controller.StateData.HistoryRec;
import com.deployguard.controller.StateData.RolloutRec;
import com.deployguard.core.ConfigBundle;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.Guardrails;
import com.deployguard.core.RolloutState;
import com.deployguard.core.RolloutState.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Staged rollouts: creation, manual actions and the leader's guardrail scheduler.
// Every mutation holds the flag/env lease and presents its fencing token.
public final class Rollouts {
    public record CreateRequest(String requestId, String flagKey, String env, List<Integer> stages, Integer observeSeconds,
                                Double maxErrorRate, Long maxP95Ms, Long minRequests, String onBreach,
                                Boolean autoPromote, FlagEnvConfig targetConfig) {}

    private final Ctx ctx;

    public Rollouts(Ctx ctx) {
        this.ctx = ctx;
    }

    static String validate(CreateRequest r) {
        if (r.flagKey() == null || r.flagKey().isBlank()) return "flagKey is required";
        if (r.env() == null || r.env().isBlank()) return "env is required";
        if (r.stages() == null || r.stages().isEmpty() || r.stages().size() > 10) return "stages must list 1 to 10 percentages";
        int prev = 0;
        for (Integer s : r.stages()) {
            if (s == null || s < 1 || s > 100) return "each stage must be between 1 and 100";
            if (s <= prev) return "stages must be strictly increasing";
            prev = s;
        }
        if (r.observeSeconds() != null && (r.observeSeconds() < 5 || r.observeSeconds() > 3600)) return "observeSeconds must be 5..3600";
        if (r.maxErrorRate() != null && (r.maxErrorRate() <= 0 || r.maxErrorRate() > 1)) return "maxErrorRate must be in (0, 1]";
        if (r.maxP95Ms() != null && r.maxP95Ms() <= 0) return "maxP95Ms must be positive";
        if (r.minRequests() != null && r.minRequests() < 1) return "minRequests must be at least 1";
        if (r.onBreach() != null && !List.of("ROLLBACK", "PAUSE").contains(r.onBreach())) return "onBreach must be ROLLBACK or PAUSE";
        return null;
    }

    static boolean active(RolloutRec r) {
        return "RUNNING".equals(r.state) || "PAUSED".equals(r.state);
    }

    public Map<String, Object> create(CreateRequest r, String actor) {
        String err = validate(r);
        if (err != null) throw new ApiServer.ApiError(400, err);
        String resource = StateStore.resource(r.flagKey(), r.env());
        Leases.Lease lease = ctx.acquireWithin(resource, ctx.holder("rollout-create"), 15_000, 2_000)
                .orElseThrow(() -> new ApiServer.ApiError(409, "Another operation holds the lease on " + resource + "; retry shortly"));
        try {
            Object[] out = ctx.store.write(st -> {
                if (r.requestId() != null) {
                    // Same request id again (a client retry): return the rollout it already created.
                    for (RolloutRec x : st.rollouts.values()) if (r.requestId().equals(x.requestId)) return new Object[]{x.id, null};
                }
                StateStore.flag(st, r.flagKey());
                for (RolloutRec x : st.rollouts.values()) {
                    if (x.flagKey.equals(r.flagKey()) && x.envKey.equals(r.env()) && active(x)) {
                        throw new ApiServer.ApiError(409, "A rollout is already active for " + r.flagKey() + " in " + r.env());
                    }
                }
                FlagEnvConfig baseline = StateStore.envConfig(st, r.flagKey(), r.env());
                FlagEnvConfig target = (r.targetConfig() != null ? r.targetConfig() : baseline).withEnabled(true);
                int first = r.stages().get(0);
                ConfigBundle b = StateStore.writeConfig(st, r.flagKey(), r.env(), target.withPercent(first),
                        "rollout stage 1 (" + first + "%)", actor, lease.token());
                long now = System.currentTimeMillis();
                RolloutRec x = new RolloutRec();
                x.id = st.nextRolloutId++;
                x.requestId = r.requestId();
                x.flagKey = r.flagKey();
                x.envKey = r.env();
                x.state = "RUNNING";
                x.stages = List.copyOf(r.stages());
                x.stageStartedAt = now;
                x.stageRevision = b.revision();
                x.observeSeconds = r.observeSeconds() == null ? 20 : r.observeSeconds();
                x.maxErrorRate = r.maxErrorRate() == null ? 0.05 : r.maxErrorRate();
                x.maxP95Ms = r.maxP95Ms() == null ? 250L : r.maxP95Ms();
                x.minRequests = r.minRequests() == null ? 50L : r.minRequests();
                x.onBreach = r.onBreach() == null ? "ROLLBACK" : r.onBreach();
                x.autoPromote = r.autoPromote() == null || r.autoPromote();
                x.baselineConfig = baseline;
                x.targetConfig = target;
                x.createdBy = actor;
                x.createdAt = now;
                x.updatedAt = now;
                st.rollouts.put(x.id, x);
                history(x, null, "RUNNING", 0, first, b.revision(), "rollout started", actor, lease.token());
                return new Object[]{x.id, b};
            });
            long id = (Long) out[0];
            if (out[1] instanceof ConfigBundle b) {
                ctx.events.record("ROLLOUT_STARTED", "ok", b.revision(), Map.of("rolloutId", id, "flag", r.flagKey(),
                        "env", r.env(), "stages", r.stages(), "fenceToken", lease.token(), "actor", actor));
            }
            return get(id).orElseThrow();
        } finally {
            ctx.releaseQuietly(lease);
        }
    }

    public Map<String, Object> act(long id, Action action, String actor, String reason) {
        RolloutRec r = ctx.store.read(st -> st.rollouts.get(id));
        if (r == null) throw new ApiServer.ApiError(404, "Rollout " + id + " not found");
        String resource = StateStore.resource(r.flagKey, r.envKey);
        Leases.Lease lease = ctx.acquireWithin(resource, ctx.holder("rollout-" + action), 15_000, 3_000)
                .orElseThrow(() -> new ApiServer.ApiError(409, "Another operation holds the lease on " + resource + "; retry shortly"));
        try {
            return apply(id, action, actor, reason == null || reason.isBlank() ? "manual " + action.name().toLowerCase() : reason, lease, null);
        } finally {
            ctx.releaseQuietly(lease);
        }
    }

    // Applies one validated transition under an already-held lease.
    private Map<String, Object> apply(long id, Action action, String actor, String reason, Leases.Lease lease,
                                      Map<String, Object> breach) {
        Object[] out = ctx.store.write(st -> {
            RolloutRec r = st.rollouts.get(id);
            if (r == null) throw new ApiServer.ApiError(404, "Rollout " + id + " not found");
            RolloutState from = RolloutState.valueOf(r.state);
            int stage = r.currentStage;
            boolean atLast = stage >= r.stages.size() - 1;
            RolloutState to;
            try {
                to = from.apply(action, atLast);
            } catch (IllegalStateException e) {
                throw new ApiServer.ApiError(409, e.getMessage());
            }
            StateStore.checkFence(st, StateStore.resource(r.flagKey, r.envKey), lease.token());
            long now = System.currentTimeMillis();
            ConfigBundle bundle = null;
            int newStage = stage;
            switch (action) {
                case PROMOTE -> {
                    if (!atLast) {
                        newStage = stage + 1;
                        int pct = r.stages.get(newStage);
                        bundle = StateStore.writeConfig(st, r.flagKey, r.envKey, r.targetConfig.withPercent(pct),
                                "rollout stage " + (newStage + 1) + " (" + pct + "%)", actor, lease.token());
                        r.currentStage = newStage;
                        r.stageStartedAt = now;
                        r.stageRevision = bundle.revision();
                    }
                }
                case RESUME -> r.stageStartedAt = now;
                case ROLLBACK -> {
                    bundle = StateStore.writeConfig(st, r.flagKey, r.envKey, r.baselineConfig, "rollback: " + reason, actor, lease.token());
                    r.rollbackReason = reason;
                    r.rollbackRevision = bundle.revision();
                }
                default -> { }
            }
            if (breach != null) {
                r.breachDetectedAt = now;
                r.lastVerdict = String.valueOf(breach.get("verdict"));
                r.lastDetail = String.valueOf(breach.get("detail"));
            }
            r.state = to.name();
            r.updatedAt = now;
            if (to.isTerminal()) r.finishedAt = now;
            int pct = action == Action.ROLLBACK ? -1 : r.stages.get(newStage);
            history(r, from.name(), to.name(), newStage, pct, bundle == null ? null : bundle.revision(), reason, actor, lease.token());
            return new Object[]{from, to, bundle, r.flagKey, r.envKey};
        });
        ConfigBundle b = (ConfigBundle) out[2];
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rolloutId", id);
        data.put("flag", out[3]);
        data.put("env", out[4]);
        data.put("from", out[0].toString());
        data.put("to", out[1].toString());
        data.put("action", action.name());
        data.put("reason", reason);
        data.put("actor", actor);
        data.put("fenceToken", lease.token());
        if (breach != null) data.putAll(breach);
        String type = switch (action) {
            case ROLLBACK -> breach != null ? "ROLLOUT_AUTO_ROLLBACK" : "ROLLOUT_ROLLBACK";
            case PAUSE -> breach != null ? "ROLLOUT_AUTO_PAUSE" : "ROLLOUT_PAUSED";
            case PROMOTE -> out[1] == RolloutState.COMPLETED ? "ROLLOUT_COMPLETED" : "ROLLOUT_PROMOTED";
            default -> "ROLLOUT_" + action.name();
        };
        ctx.events.record(type, breach != null ? "breach" : "ok", b == null ? null : b.revision(), data);
        return get(id).orElseThrow();
    }

    private void history(RolloutRec r, String from, String to, int stage, int pct, Long revision, String reason,
                         String actor, long token) {
        HistoryRec h = new HistoryRec();
        h.id = r.history.size() + 1;
        h.fromState = from;
        h.toState = to;
        h.stage = stage;
        h.percent = pct;
        h.revision = revision;
        h.reason = reason;
        h.actor = actor;
        h.fenceToken = token;
        h.lamport = ctx.clock.lamport();
        h.at = System.currentTimeMillis();
        r.history.add(h);
    }

    public Optional<Map<String, Object>> get(long id) {
        return ctx.store.read(st -> Optional.ofNullable(st.rollouts.get(id)).map(StateStore::view));
    }

    public List<Map<String, Object>> list(int limit) {
        return ctx.store.read(st -> {
            List<Map<String, Object>> out = new ArrayList<>();
            List<RolloutRec> all = new ArrayList<>(st.rollouts.values());
            for (int i = all.size() - 1; i >= 0 && out.size() < Math.max(1, Math.min(limit, 200)); i--) {
                Map<String, Object> v = StateStore.view(all.get(i));
                v.remove("history");
                out.add(v);
            }
            return out;
        });
    }

    // ---------- leader scheduler ----------

    public void schedulerTick() {
        List<Long> running = ctx.store.read(st -> st.rollouts.values().stream().filter(r -> "RUNNING".equals(r.state)).map(r -> r.id).toList());
        for (long id : running) {
            try {
                evaluate(id);
            } catch (RuntimeException e) {
                Log.error("scheduler_evaluate_failed", e, "rollout", id);
            }
        }
    }

    private void evaluate(long id) {
        RolloutRec r = ctx.store.read(st -> st.rollouts.get(id));
        if (r == null || !"RUNNING".equals(r.state)) return;
        Optional<Leases.Lease> lease = ctx.leases.acquire(StateStore.resource(r.flagKey, r.envKey), ctx.holder("scheduler"), 5_000);
        if (lease.isEmpty()) return;
        try {
            StateStore.WindowStats s = ctx.store.stats(r.flagKey, r.envKey, "on", r.stageRevision, r.stageStartedAt);
            Guardrails.Policy p = new Guardrails.Policy(r.maxErrorRate, r.maxP95Ms, r.minRequests);
            Guardrails.Result res = Guardrails.evaluate(p, new Guardrails.Stats(s.requests(), s.errors(), s.p95Ms()));
            int[] conv = convergence(r.envKey, r.stageRevision);
            String detail = res.detail() + "; converged " + conv[0] + "/" + conv[1] + " live agents";
            ctx.store.write(st -> {
                RolloutRec x = st.rollouts.get(id);
                if (x != null && "RUNNING".equals(x.state)) {
                    x.lastVerdict = res.verdict().name();
                    x.lastDetail = detail;
                }
                return null;
            });
            if (res.verdict() == Guardrails.Verdict.BREACH_ERROR_RATE || res.verdict() == Guardrails.Verdict.BREACH_LATENCY) {
                Action a = "PAUSE".equals(r.onBreach) ? Action.PAUSE : Action.ROLLBACK;
                Map<String, Object> breach = new LinkedHashMap<>();
                breach.put("verdict", res.verdict().name());
                breach.put("detail", res.detail());
                breach.put("requests", s.requests());
                breach.put("errors", s.errors());
                breach.put("p95Ms", s.p95Ms());
                apply(id, a, ctx.id + " (guardrail)", "guardrail " + res.verdict().name() + ": " + res.detail(), lease.get(), breach);
                return;
            }
            boolean windowDone = System.currentTimeMillis() - r.stageStartedAt >= r.observeSeconds * 1000L;
            boolean converged = conv[1] > 0 && conv[0] == conv[1];
            if (windowDone && converged && res.verdict() == Guardrails.Verdict.HEALTHY && r.autoPromote) {
                apply(id, Action.PROMOTE, ctx.id + " (scheduler)", "guardrails healthy: " + res.detail(), lease.get(), null);
            }
        } finally {
            ctx.releaseQuietly(lease.get());
        }
    }

    // [agents at or past the revision, live agents] for one environment.
    int[] convergence(String env, long revision) {
        int ok = 0;
        int live = 0;
        for (Map<String, Object> a : ctx.store.agents(ctx.fd, false)) {
            if (!env.equals(a.get("envKey")) || !Boolean.TRUE.equals(a.get("live"))) continue;
            live++;
            if (((Number) a.get("appliedRevision")).longValue() >= revision) ok++;
        }
        return new int[]{ok, live};
    }
}

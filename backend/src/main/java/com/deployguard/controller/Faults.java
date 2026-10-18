package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Retry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Fault injection. Every injected fault is recorded as a simulated event so it is never mistaken for a real failure.
public final class Faults {
    public static final Set<String> AGENT_FAULTS = Set.of("delay", "partition", "crash", "unhealthy", "clear");

    private final Ctx ctx;

    public Faults(Ctx ctx) {
        this.ctx = ctx;
    }

    static int seconds(Map<String, Object> f, int def, int max) {
        Object s = f.get("seconds");
        int v = s instanceof Number n ? n.intValue() : def;
        if (v < 1 || v > max) throw new ApiServer.ApiError(400, "seconds must be 1.." + max);
        return v;
    }

    // Validates and forwards an agent fault through the dg.control exchange. agentId null means all agents.
    public Map<String, Object> agentFault(String agentId, Map<String, Object> f) throws Exception {
        String type = String.valueOf(f.get("type"));
        if (!AGENT_FAULTS.contains(type)) throw new ApiServer.ApiError(400, "type must be one of " + AGENT_FAULTS);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        switch (type) {
            case "delay" -> {
                Object d = f.get("delayMs");
                int delay = d instanceof Number n ? n.intValue() : 2000;
                if (delay < 1 || delay > 30_000) throw new ApiServer.ApiError(400, "delayMs must be 1..30000");
                msg.put("delayMs", delay);
                msg.put("seconds", seconds(f, 60, 600));
            }
            case "partition" -> msg.put("seconds", seconds(f, 10, 300));
            case "unhealthy" -> {
                Object er = f.get("errorRate");
                double rate = er instanceof Number n ? n.doubleValue() : 0.3;
                if (rate < 0 || rate > 1) throw new ApiServer.ApiError(400, "errorRate must be 0..1");
                Object el = f.get("extraLatencyMs");
                int lat = el instanceof Number n ? n.intValue() : 0;
                if (lat < 0 || lat > 5000) throw new ApiServer.ApiError(400, "extraLatencyMs must be 0..5000");
                msg.put("errorRate", rate);
                msg.put("extraLatencyMs", lat);
                msg.put("flag", f.get("flag") == null ? "*" : String.valueOf(f.get("flag")));
                msg.put("seconds", seconds(f, 120, 600));
            }
            default -> { }
        }
        ctx.sendControl(agentId, "FAULT", msg);
        Map<String, Object> ev = new LinkedHashMap<>(msg);
        ev.put("target", agentId == null ? "all agents" : agentId);
        ctx.events.record("FAULT_INJECTED", "injected", null, ev, true);
        return ev;
    }

    // Faults that act on this controller process.
    public Map<String, Object> controllerFault(Map<String, Object> f) {
        String action = String.valueOf(f.get("action"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("target", ctx.id);
        ev.put("action", action);
        switch (action) {
            case "isolate" -> {
                int s = seconds(f, 10, 120);
                ctx.isolatedUntilMs = System.currentTimeMillis() + s * 1000L;
                ev.put("seconds", s);
            }
            case "step-down" -> {
                // Stop acting as leader and stay silent long enough for the others to notice.
                ctx.isolatedUntilMs = System.currentTimeMillis() + 5000;
                ctx.election.forgetLeader();
            }
            case "crash" -> Thread.ofVirtual().start(() -> {
                Retry.sleepQuietly(300);
                Log.warn("simulated_crash", "node", ctx.id);
                Runtime.getRuntime().halt(1);
            });
            default -> throw new ApiServer.ApiError(400, "action must be isolate, step-down or crash");
        }
        // When the request came through another controller, that controller records the event,
        // because an isolated node could not deliver its own event until the partition heals.
        if (!Boolean.TRUE.equals(f.get("remote"))) ctx.events.record("FAULT_INJECTED", "injected", null, ev, true);
        return ev;
    }

    static List<String> agentFaultTypes() {
        return List.copyOf(AGENT_FAULTS);
    }
}

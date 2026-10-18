package com.deployguard.controller;

import com.deployguard.common.Log;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Leader-only: turns failure-detector state changes into events (suspected, disconnected, stale, recovered).
final class HealthMonitor {
    private static final Set<String> NOTABLE = Set.of("SUSPECTED", "DISCONNECTED", "STALE", "RECOVERING");

    private final Ctx ctx;
    private Map<String, String> last = null;

    HealthMonitor(Ctx ctx) {
        this.ctx = ctx;
    }

    void tick(boolean leader) {
        if (!leader) {
            // Forget state so a newly elected leader starts from what it observes, without replaying old changes.
            last = null;
            return;
        }
        List<Map<String, Object>> agents;
        try {
            agents = ctx.store.agents(ctx.fd, false);
        } catch (Exception e) {
            Log.warn("health_monitor_failed", "error", e.getMessage());
            return;
        }
        Map<String, String> now = new HashMap<>();
        for (Map<String, Object> a : agents) now.put((String) a.get("id"), (String) a.get("health"));
        if (last != null) {
            for (Map.Entry<String, String> e : now.entrySet()) {
                String before = last.get(e.getKey());
                String after = e.getValue();
                if (before == null || before.equals(after)) continue;
                if (!NOTABLE.contains(before) && !NOTABLE.contains(after)) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("agentId", e.getKey());
                d.put("from", before);
                d.put("to", after);
                String type = switch (after) {
                    case "SUSPECTED" -> "AGENT_SUSPECTED";
                    case "DISCONNECTED" -> "AGENT_DISCONNECTED";
                    case "STALE" -> "AGENT_STALE";
                    case "HEALTHY", "RECOVERING", "DELAYED" -> Set.of("SUSPECTED", "DISCONNECTED", "STALE").contains(before)
                            ? "AGENT_RECOVERED" : "AGENT_HEALTH_CHANGED";
                    default -> "AGENT_HEALTH_CHANGED";
                };
                ctx.events.record(type, after.equals("SUSPECTED") || after.equals("DISCONNECTED") ? "suspected" : "ok", null, d);
            }
        }
        last = now;
    }
}

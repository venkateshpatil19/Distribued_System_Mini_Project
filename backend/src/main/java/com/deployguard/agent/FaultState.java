package com.deployguard.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Faults injected into this agent. All of them are simulations and expire on their own.
final class FaultState {
    private long delayUntil;
    private long delayMs;
    private long partitionUntil;
    private long unhealthyUntil;
    private String unhealthyFlag = "*";
    private double unhealthyErrorRate;
    private long unhealthyExtraLatencyMs;

    synchronized void apply(Map<String, Object> f, long now) {
        String type = String.valueOf(f.get("type"));
        long seconds = f.get("seconds") instanceof Number n ? n.longValue() : 30;
        switch (type) {
            case "delay" -> {
                delayMs = ((Number) f.getOrDefault("delayMs", 2000)).longValue();
                delayUntil = now + seconds * 1000;
            }
            case "partition" -> partitionUntil = now + seconds * 1000;
            case "unhealthy" -> {
                unhealthyFlag = String.valueOf(f.getOrDefault("flag", "*"));
                unhealthyErrorRate = ((Number) f.getOrDefault("errorRate", 0.3)).doubleValue();
                unhealthyExtraLatencyMs = ((Number) f.getOrDefault("extraLatencyMs", 0)).longValue();
                unhealthyUntil = now + seconds * 1000;
            }
            case "clear" -> {
                delayUntil = 0;
                partitionUntil = 0;
                unhealthyUntil = 0;
            }
            default -> { }
        }
    }

    synchronized long delayMs(long now) {
        return now < delayUntil ? delayMs : 0;
    }

    synchronized boolean partitioned(long now) {
        return now < partitionUntil;
    }

    // Unhealthy behaviour applies only to the "on" variant of the targeted flag (the new code path).
    synchronized boolean unhealthyFor(String flag, long now) {
        return now < unhealthyUntil && ("*".equals(unhealthyFlag) || unhealthyFlag.equals(flag));
    }

    synchronized double unhealthyErrorRate() {
        return unhealthyErrorRate;
    }

    synchronized long unhealthyExtraLatencyMs() {
        return unhealthyExtraLatencyMs;
    }

    synchronized List<String> active(long now) {
        List<String> out = new ArrayList<>();
        if (now < delayUntil) out.add("delay:" + delayMs + "ms");
        if (now < partitionUntil) out.add("partition");
        if (now < unhealthyUntil) out.add("unhealthy:" + unhealthyFlag);
        return out;
    }
}

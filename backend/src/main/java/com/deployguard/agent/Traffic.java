package com.deployguard.agent;

import com.deployguard.core.ConfigBundle;
import com.deployguard.core.FlagEvaluator;
import com.deployguard.core.Histogram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;


// Simulated request traffic. Each synthetic request evaluates every flag for this environment and
// records an outcome per (flag, variant). Latency and errors are generated, not measured.
final class Traffic {
    static final String ALL = "__all__";
    private static final String[] COUNTRIES = {"IN", "US", "DE", "GB", "BR", "JP"};
    private static final String[] PLANS = {"free", "pro", "enterprise"};
    static final double BASE_ERROR_RATE = 0.005;

    private static final class Window {
        long requests;
        long errors;
        final Histogram hist = new Histogram();
    }

    private final Random rnd;
    private final Map<String, Window> windows = new HashMap<>();
    private double carry;
    long totalRequests;

    Traffic(long seed) {
        this.rnd = new Random(seed);
    }

    // Generates the requests for one tick of tickMs at the given rate.
    synchronized void tick(ConfigBundle bundle, String env, int rps, long tickMs, FaultState faults, long now) {
        carry += rps * tickMs / 1000.0;
        int n = (int) carry;
        carry -= n;
        for (int i = 0; i < n; i++) one(bundle, env, faults, now);
    }

    private void one(ConfigBundle bundle, String env, FaultState faults, long now) {
        Map<String, String> ctx = Map.of("userId", "u" + rnd.nextInt(5000),
                "country", COUNTRIES[rnd.nextInt(COUNTRIES.length)], "plan", PLANS[rnd.nextInt(PLANS.length)]);
        double base = Math.max(5, 32 + rnd.nextGaussian() * 9);
        double total = base;
        boolean anyError = false;
        for (ConfigBundle.FlagSnapshot f : bundle.flags().values()) {
            ConfigBundle.FlagEnvConfig cfg = f.envs().get(env);
            if (cfg == null) continue;
            String variant = FlagEvaluator.evaluate(f.key(), cfg, ctx).variant();
            boolean bad = "on".equals(variant) && faults.unhealthyFor(f.key(), now);
            double extra = bad ? faults.unhealthyExtraLatencyMs() : 0;
            boolean err = rnd.nextDouble() < (bad ? faults.unhealthyErrorRate() : BASE_ERROR_RATE);
            anyError |= err;
            total += extra;
            // Per-flag latency counts only that flag's own code path, so one bad flag cannot fail another's guardrail.
            record(f.key(), variant, err, base + extra);
        }
        record(ALL, "all", anyError, total);
        totalRequests++;
    }

    private void record(String flag, String variant, boolean error, double latency) {
        Window w = windows.computeIfAbsent(flag + "|" + variant, k -> new Window());
        w.requests++;
        if (error) w.errors++;
        w.hist.record(latency);
    }

    // Returns the window rows and resets them.
    synchronized List<Map<String, Object>> drain() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            if (e.getValue().requests == 0) continue;
            String[] k = e.getKey().split("\\|", 2);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("flag", k[0]);
            r.put("variant", k[1]);
            r.put("requests", e.getValue().requests);
            r.put("errors", e.getValue().errors);
            r.put("hist", e.getValue().hist.counts());
            rows.add(r);
        }
        windows.clear();
        return rows;
    }
}

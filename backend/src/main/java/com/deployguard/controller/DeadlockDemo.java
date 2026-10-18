package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Retry;
import com.deployguard.core.WaitForGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Deadlock detection over real leases. Workflows lock demo resources in conflicting orders and record
// wait-for edges; a centralized detector builds the global wait-for graph, finds a cycle and aborts
// the youngest workflow (largest Lamport start time) to break it.
public final class DeadlockDemo {
    private final Ctx ctx;
    // waiter -> (resource -> holder), the edges reported by blocked workflows.
    private final Map<String, Map<String, String>> waits = new ConcurrentHashMap<>();

    public DeadlockDemo(Ctx ctx) {
        this.ctx = ctx;
    }

    static Map<String, List<String>> scenario(String name) {
        Map<String, List<String>> s = new LinkedHashMap<>();
        switch (name) {
            case "two-way" -> {
                s.put("W1", List.of("A", "B"));
                s.put("W2", List.of("B", "A"));
            }
            case "three-way" -> {
                s.put("W1", List.of("A", "B"));
                s.put("W2", List.of("B", "C"));
                s.put("W3", List.of("C", "A"));
            }
            case "ordered" -> {
                // Prevention by global resource ordering: both lock A before B, so no cycle can form.
                s.put("W1", List.of("A", "B"));
                s.put("W2", List.of("A", "B"));
            }
            default -> throw new ApiServer.ApiError(400, "scenario must be two-way, three-way or ordered");
        }
        return s;
    }

    public Map<String, Object> run(String scenarioName) throws Exception {
        Map<String, List<String>> scen = scenario(scenarioName);
        String runId = "dl" + (System.currentTimeMillis() % 1_000_000);
        Map<String, String> holderOf = new LinkedHashMap<>();
        Map<String, Long> startLamport = new ConcurrentHashMap<>();
        Set<String> aborted = ConcurrentHashMap.newKeySet();
        Map<String, String> outcome = new ConcurrentHashMap<>();
        List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        for (String w : scen.keySet()) holderOf.put(w, runId + "-" + w);
        long t0 = System.currentTimeMillis();
        CountDownLatch finished = new CountDownLatch(scen.size());

        for (Map.Entry<String, List<String>> e : scen.entrySet()) {
            String w = e.getKey();
            Thread.ofVirtual().start(() -> {
                String holder = holderOf.get(w);
                List<Leases.Lease> held = new ArrayList<>();
                try {
                    startLamport.put(w, ctx.clock.tick().lamport());
                    for (String res : e.getValue()) {
                        String name = "demo:" + runId + ":" + res;
                        while (true) {
                            if (aborted.contains(w)) throw new InterruptedException("aborted");
                            Optional<Leases.Lease> l = ctx.leases.acquire(name, holder, 30_000);
                            if (l.isPresent()) {
                                held.add(l.get());
                                clearWait(holder, name);
                                timeline.add((System.currentTimeMillis() - t0) + "ms " + w + " acquired " + res);
                                break;
                            }
                            Optional<Leases.Lease> cur = ctx.leases.current(name);
                            if (cur.isPresent()) recordWait(holder, name, cur.get().holder());
                            Retry.sleepQuietly(150);
                        }
                        Retry.sleepQuietly(400);
                    }
                    timeline.add((System.currentTimeMillis() - t0) + "ms " + w + " completed its work");
                    outcome.put(w, "COMPLETED");
                } catch (InterruptedException ie) {
                    timeline.add((System.currentTimeMillis() - t0) + "ms " + w + " aborted and released its leases");
                    outcome.put(w, "ABORTED");
                } catch (Exception ex) {
                    outcome.put(w, "ERROR: " + ex.getMessage());
                } finally {
                    for (Leases.Lease l : held) ctx.releaseQuietly(l);
                    waits.remove(holder);
                    finished.countDown();
                }
            });
        }

        List<Map<String, Object>> detections = new ArrayList<>();
        while (!finished.await(300, TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() - t0 > 20_000) break;
            WaitForGraph g = graph(runId);
            List<String> cycle = g.findCycle();
            if (cycle.isEmpty()) continue;
            String victimHolder = Collections.max(cycle, (a, b) -> Long.compare(
                    startLamport.getOrDefault(shortName(a), 0L), startLamport.getOrDefault(shortName(b), 0L)));
            String victim = shortName(victimHolder);
            if (!aborted.add(victim)) continue;
            List<String> named = cycle.stream().map(DeadlockDemo::shortName).toList();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cycle", named);
            d.put("victim", victim);
            d.put("rule", "abort the youngest workflow (largest Lamport start time)");
            d.put("detectedAfterMs", System.currentTimeMillis() - t0);
            d.put("graph", printable(g));
            detections.add(d);
            timeline.add((System.currentTimeMillis() - t0) + "ms detector found cycle " + named + ", aborting " + victim);
            ctx.events.record("DEADLOCK_DETECTED", "resolved", null, Map.of("run", runId, "cycle", named, "victim", victim));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("scenario", scenarioName);
        result.put("workflows", scen);
        result.put("startLamport", startLamport);
        result.put("detections", detections);
        result.put("outcome", outcome);
        result.put("timeline", new ArrayList<>(timeline));
        result.put("deadlockFound", !detections.isEmpty());
        result.put("knappCategory", "Centralized detection over a global wait-for graph (global-state style); see docs/ALGORITHMS.md");
        if (detections.isEmpty()) {
            ctx.events.record("DEADLOCK_CHECK", "no-deadlock", null, Map.of("run", runId, "scenario", scenarioName));
        }
        Log.info("deadlock_demo", "run", runId, "detections", detections.size());
        return result;
    }

    private static String shortName(String holder) {
        int i = holder.lastIndexOf('-');
        return i < 0 ? holder : holder.substring(i + 1);
    }

    // Edge waiter -> current holder of the resource it waits for.
    private WaitForGraph graph(String runId) {
        WaitForGraph g = new WaitForGraph();
        for (Map.Entry<String, Map<String, String>> w : waits.entrySet()) {
            for (String res : w.getValue().keySet()) {
                if (!res.startsWith("demo:" + runId + ":")) continue;
                ctx.leases.current(res).ifPresent(l -> g.addEdge(w.getKey(), l.holder()));
            }
        }
        return g;
    }

    private static Map<String, Object> printable(WaitForGraph g) {
        Map<String, Object> out = new LinkedHashMap<>();
        g.edges().forEach((k, v) -> out.put(shortName(k), v.stream().map(DeadlockDemo::shortName).toList()));
        return out;
    }

    private void recordWait(String waiter, String resource, String holder) {
        waits.computeIfAbsent(waiter, k -> new ConcurrentHashMap<>()).put(resource, holder);
    }

    private void clearWait(String waiter, String resource) {
        Map<String, String> m = waits.get(waiter);
        if (m != null) m.remove(resource);
    }
}

package com.deployguard.agent;

import com.deployguard.common.Env;
import com.deployguard.common.Json;
import com.deployguard.common.Log;
import com.deployguard.common.Msg;
import com.deployguard.common.NodeClock;
import com.deployguard.common.Retry;
import com.deployguard.core.ConfigBundle;
import com.deployguard.rpc.Rpc;
import com.sun.net.httpserver.HttpServer;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Agent process: registers with the leader controller, sends heartbeats, receives commands in the
// heartbeat replies, pulls new configuration revisions, generates simulated traffic and reports metrics.
public final class AgentMain {
    private final String id;
    private final String env;
    private final long incarnation = System.currentTimeMillis();
    private final long skewMs;
    private final NodeClock clock;
    private final FaultState faults = new FaultState();
    private final Traffic traffic;
    private final Map<Integer, ManagedChannel> channels = new TreeMap<>();
    private volatile int target;

    private volatile ConfigBundle bundle = ConfigBundle.empty();
    private volatile boolean registered;
    private volatile boolean controllerReachable;
    private volatile String lastController;
    private volatile long desiredRevision;
    private volatile Long clockOffsetMs;
    private volatile Long rttMs;
    private volatile boolean trafficEnabled = true;
    private volatile int rps = Env.integer("DG_TRAFFIC_RPS", 40);
    private int heartbeatFailures;
    private long nextHeartbeatAt;
    private final AtomicBoolean pulling = new AtomicBoolean();
    // Reports to the leader, sent in order (this is our outgoing FIFO channel).
    private final Deque<Msg> outbox = new ArrayDeque<>();
    private final AtomicLong duplicatesIgnored = new AtomicLong();
    private final AtomicLong pulls = new AtomicLong();
    private final AtomicLong acksSent = new AtomicLong();

    private AgentMain(String id, String env, Map<Integer, String> controllers, long skewMs) {
        this.id = id;
        this.env = env;
        this.skewMs = skewMs;
        this.clock = new NodeClock(id);
        this.traffic = new Traffic(id.hashCode() ^ incarnation);
        controllers.forEach((n, addr) -> channels.put(n, ManagedChannelBuilder.forTarget(addr).usePlaintext().build()));
        this.target = channels.keySet().iterator().next();
    }

    // Physical clock with the simulated skew applied (Cristian's algorithm estimates and reports it).
    private long localNow() {
        return System.currentTimeMillis() + skewMs;
    }

    // CONTROLLERS="1=localhost:9091,2=localhost:9092,3=localhost:9093"
    static Map<Integer, String> parseControllers(String spec) {
        Map<Integer, String> out = new TreeMap<>();
        int n = 1;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq > 0) out.put(Integer.parseInt(p.substring(0, eq).trim()), p.substring(eq + 1).trim());
            else out.put(n, p);
            n++;
        }
        if (out.isEmpty()) throw new IllegalArgumentException("CONTROLLERS is empty");
        return out;
    }

    public static void main(String[] args) throws Exception {
        // Scaled Docker replicas share one config, so each names itself after its container (HOSTNAME).
        String id = Env.str("DG_AGENT_ID", "agent-" + Env.str("HOSTNAME", String.valueOf(ThreadLocalRandom.current().nextInt(1000, 9999))));
        Log.setNode(id);
        String skew = Env.str("CLOCK_SKEW_MS", "random");
        long skewMs = "random".equals(skew) ? ThreadLocalRandom.current().nextLong(-400, 401) : Long.parseLong(skew);
        Map<Integer, String> controllers = parseControllers(Env.str("CONTROLLERS", Env.str("CONTROLLER_TARGET", "localhost:9090")));
        new AgentMain(id, Env.str("DG_ENV", "production"), controllers, skewMs).run();
    }

    private <Q, R> R call(MethodDescriptor<Q, R> m, Q req, long timeoutMs) {
        return ClientCalls.blockingUnaryCall(channels.get(target), m,
                CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS), req);
    }

    // Switch to the controller named as leader, or try the next one when nobody is named.
    private void follow(Integer leaderId) {
        int before = target;
        if (leaderId != null && channels.containsKey(leaderId)) target = leaderId;
        else {
            List<Integer> ids = new ArrayList<>(channels.keySet());
            target = ids.get((ids.indexOf(target) + 1) % ids.size());
        }
        if (before != target) {
            Log.info("switch_controller", "from", before, "to", target);
            registered = false;
        }
    }

    private void run() throws Exception {
        startHttp(Env.integer("HTTP_PORT", 8081));
        Log.info("agent_started", "env", env, "skewMs", skewMs, "incarnation", incarnation, "controllers", channels.keySet());

        long hbMs = Env.lng("HEARTBEAT_MS", 1000);
        ScheduledExecutorService s = Executors.newScheduledThreadPool(4);
        s.scheduleWithFixedDelay(guard("heartbeat", () -> heartbeatLoop(hbMs)), 0, 200, TimeUnit.MILLISECONDS);
        s.scheduleWithFixedDelay(guard("traffic", () -> {
            if (trafficEnabled && registered) traffic.tick(bundle, env, rps, 100, faults, System.currentTimeMillis());
        }), 1000, 100, TimeUnit.MILLISECONDS);
        s.scheduleWithFixedDelay(guard("metrics", this::flushMetrics), 2000, 2000, TimeUnit.MILLISECONDS);
        s.scheduleWithFixedDelay(guard("reports", this::sendReports), 1000, 300, TimeUnit.MILLISECONDS);
        s.scheduleWithFixedDelay(guard("cristian", this::syncClock), 2000, 10_000, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("agent_stopping");
            s.shutdownNow();
            channels.values().forEach(ManagedChannel::shutdownNow);
        }));
        Thread.currentThread().join();
    }

    private static Runnable guard(String name, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.error("loop_failed", t, "loop", name);
            }
        };
    }

    // ---------- registration and heartbeats ----------

    private void heartbeatLoop(long hbMs) {
        long now = System.currentTimeMillis();
        if (now < nextHeartbeatAt) return;
        boolean partitioned = faults.partitioned(now);
        try {
            if (!registered && !partitioned && !register()) {
                nextHeartbeatAt = now + 300;
                return;
            }
            NodeClock.Stamp st = clock.tick();
            Rpc.HeartbeatResponse r = call(Rpc.HEARTBEAT, new Rpc.HeartbeatRequest(id, env, incarnation, bundle.revision(),
                    st.lamport(), st.vclock(), clockOffsetMs, rttMs, faults.active(now), localNow()), 1500);
            nextHeartbeatAt = now + hbMs;
            if (!r.accepted()) {
                follow(r.leaderId());
                nextHeartbeatAt = now + 100;
                return;
            }
            heartbeatFailures = 0;
            for (Msg m : r.commands() == null ? List.<Msg>of() : r.commands()) onCommand(m);
            if (partitioned) {
                // Simulated partition: the poll above only fetches fault commands; nothing else gets through.
                controllerReachable = false;
                return;
            }
            clock.receive(r.lamport(), r.vclock());
            controllerReachable = true;
            lastController = r.controllerId();
            if (!r.known()) {
                Log.warn("agent_unknown_to_controller", "via", r.controllerId());
                registered = false;
                return;
            }
            desiredRevision = r.desiredRevision();
            if (bundle.revision() < desiredRevision) pullAsync();
        } catch (RuntimeException e) {
            controllerReachable = false;
            follow(null);
            long delay = Retry.backoffMs(heartbeatFailures++, 200, 3000, ThreadLocalRandom.current().nextDouble());
            nextHeartbeatAt = now + delay;
            Log.warn("heartbeat_failed", "attempt", heartbeatFailures, "retryInMs", delay, "error", e.getMessage());
        }
    }

    private boolean register() {
        NodeClock.Stamp st = clock.tick();
        Rpc.RegisterResponse r = call(Rpc.REGISTER, new Rpc.RegisterRequest(id, env, id + ":" + Env.integer("HTTP_PORT", 8081),
                incarnation, bundle.revision(), st.lamport(), st.vclock()), 3000);
        if (!r.accepted()) {
            follow(r.leaderId());
            return false;
        }
        clock.receive(r.lamport(), r.vclock());
        registered = true;
        desiredRevision = r.desiredRevision();
        Log.info("agent_registered", "via", r.controllerId(), "desiredRevision", r.desiredRevision());
        return true;
    }

    // Fetches the latest bundle in the background. A "delay" fault holds the update back first.
    private void pullAsync() {
        if (!pulling.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                long delay = faults.delayMs(System.currentTimeMillis());
                if (delay > 0) Retry.sleepQuietly(delay);
                if (faults.partitioned(System.currentTimeMillis())) return;
                ConfigBundle b = call(Rpc.FETCH_CONFIG, new Rpc.FetchConfigRequest(id, env, bundle.revision()), 3000);
                pulls.incrementAndGet();
                apply(b, delay > 0 ? "pull-delayed" : "pull");
            } catch (RuntimeException e) {
                Log.warn("pull_failed", "error", e.getMessage());
            } finally {
                pulling.set(false);
            }
        });
    }

    // ---------- commands from the leader ----------

    private void onCommand(Msg m) {
        long now = System.currentTimeMillis();
        if (faults.partitioned(now) && !"FAULT".equals(m.type())) return;
        clock.receive(m.lamport(), m.vclock());
        switch (m.type()) {
            case "SNAPSHOT_MARKER" -> onMarker(m);
            case "FAULT" -> {
                Map<String, Object> f = m.body();
                faults.apply(f, now);
                Log.warn("fault_applied", "fault", f);
                if ("crash".equals(f.get("type"))) {
                    Log.warn("simulated_crash");
                    Runtime.getRuntime().halt(1);
                }
            }
            case "TRAFFIC" -> {
                trafficEnabled = Boolean.TRUE.equals(m.body().get("enabled"));
                if (m.body().get("rps") instanceof Number n) rps = n.intValue();
            }
            default -> Log.warn("unknown_command", "type", m.type());
        }
    }

    private synchronized void apply(ConfigBundle b, String via) {
        if (b == null) return;
        if (b.revision() <= bundle.revision()) {
            // Late or duplicate delivery: a higher revision is already applied.
            duplicatesIgnored.incrementAndGet();
            return;
        }
        long from = bundle.revision();
        // Close the metrics window first so its numbers stay attributed to the old revision.
        flushMetrics();
        bundle = b;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", b.revision());
        body.put("fromRevision", from);
        body.put("via", via);
        body.put("outcome", "applied");
        report("CONFIG_APPLIED", body);
        acksSent.incrementAndGet();
        Log.info("config_applied", "revision", b.revision(), "via", via);
    }

    private void onMarker(Msg m) {
        // Chandy-Lamport: record local state on the marker, then send the marker on our outgoing channel.
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("appliedRevision", bundle.revision());
        state.put("activeFaults", faults.active(System.currentTimeMillis()));
        state.put("requestsGenerated", traffic.totalRequests);
        state.put("acksSent", acksSent.get());
        state.put("lamport", clock.lamport());
        state.put("vclock", clock.vector());
        report("SNAPSHOT_MARKER", Map.of("snapshotId", m.body().get("snapshotId"), "state", state));
    }

    private void flushMetrics() {
        List<Map<String, Object>> rows = traffic.drain();
        if (rows.isEmpty() || faults.partitioned(System.currentTimeMillis())) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("env", env);
        body.put("revision", bundle.revision());
        body.put("rows", rows);
        body.put("simulated", true);
        report("METRICS", body);
    }

    private void report(String type, Map<String, Object> body) {
        Msg msg = Msg.of(type, clock.tick(), body, localNow());
        synchronized (outbox) {
            outbox.add(msg);
            while (outbox.size() > 2000) outbox.poll();
        }
    }

    // Sends queued reports to the leader. They stay queued until the leader accepts them; the leader
    // drops duplicates by message id, so a resend after a lost reply is harmless.
    private void sendReports() {
        if (!registered || faults.partitioned(System.currentTimeMillis())) return;
        List<Msg> batch;
        synchronized (outbox) {
            if (outbox.isEmpty()) return;
            batch = new ArrayList<>(outbox).subList(0, Math.min(outbox.size(), 200));
        }
        try {
            Rpc.ReportResponse r = call(Rpc.REPORT, new Rpc.ReportRequest(id, batch), 3000);
            if (!r.accepted()) {
                follow(r.leaderId());
                return;
            }
            synchronized (outbox) {
                for (int i = 0; i < batch.size() && !outbox.isEmpty(); i++) outbox.poll();
            }
        } catch (RuntimeException e) {
            Log.warn("report_failed", "error", e.getMessage());
        }
    }

    // ---------- Cristian's algorithm ----------

    private void syncClock() {
        if (faults.partitioned(System.currentTimeMillis())) return;
        long t0 = localNow();
        Rpc.TimeResponse r = call(Rpc.GET_TIME, new Rpc.TimeRequest(id, t0), 2000);
        long t1 = localNow();
        long rtt = t1 - t0;
        // Estimated server time at receipt is serverTime + rtt/2; offset is how far our clock is from it.
        long offset = (r.serverTimeMs() + rtt / 2) - t1;
        rttMs = rtt;
        clockOffsetMs = offset;
    }

    // ---------- HTTP probes ----------

    private void startHttp(int port) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 16);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            int status = 200;
            Object body;
            switch (p) {
                case "/healthz" -> body = Map.of("status", "ok", "agent", id);
                case "/readyz" -> {
                    boolean ready = registered && controllerReachable;
                    status = ready ? 200 : 503;
                    body = Map.of("ready", ready);
                }
                case "/state" -> body = state();
                default -> {
                    status = 404;
                    body = Map.of("error", "not found");
                }
            }
            byte[] b = Json.bytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        http.start();
    }

    private Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("env", env);
        s.put("incarnation", incarnation);
        s.put("registered", registered);
        s.put("controllerReachable", controllerReachable);
        s.put("lastController", lastController);
        s.put("appliedRevision", bundle.revision());
        s.put("desiredRevision", desiredRevision);
        s.put("faults", faults.active(System.currentTimeMillis()));
        s.put("simulatedSkewMs", skewMs);
        s.put("cristianOffsetMs", clockOffsetMs);
        s.put("rttMs", rttMs);
        s.put("lamport", clock.lamport());
        s.put("duplicatesIgnored", duplicatesIgnored.get());
        s.put("pulls", pulls.get());
        s.put("pendingReports", outbox.size());
        s.put("trafficEnabled", trafficEnabled);
        s.put("rps", rps);
        return s;
    }
}

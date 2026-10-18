package com.deployguard.controller;

import com.deployguard.common.Env;
import com.deployguard.common.Json;
import com.deployguard.common.Log;
import com.deployguard.common.Msg;
import com.deployguard.core.BullyElection;
import com.deployguard.core.FailureDetector;
import com.deployguard.rpc.Rpc;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Controller process: HTTP API, gRPC for agents and peers, Bully election, state replication and the
// leader's scheduler. State lives in memory and is saved to data/controller-N.json.
public final class ControllerMain {
    private ControllerMain() {}

    public static void main(String[] args) throws Exception {
        int numericId = Env.integer("DG_CONTROLLER_ID", 1);
        String id = "controller-" + numericId;
        Log.setNode(id);
        String version = Env.str("DG_VERSION", "1.0.0");
        int httpPort = Env.integer("HTTP_PORT", 8080);
        int grpcPort = Env.integer("GRPC_PORT", 9090);
        FailureDetector.Settings fd = new FailureDetector.Settings(
                Env.lng("HEARTBEAT_MS", 1000), Env.lng("SUSPECT_AFTER_MS", 3000), Env.lng("DISCONNECT_AFTER_MS", 8000),
                Env.lng("STALE_AFTER_MS", 4000), Env.lng("RECOVERING_FOR_MS", 10000));

        StateStore store = new StateStore(Path.of(Env.str("DATA_DIR", "data"), id + ".json"));
        store.load();
        Ctx ctx = new Ctx(id, numericId, version, store, fd);

        PeerClient peers = new PeerClient(ctx, PeerClient.parsePeers(Env.str("PEERS", ""), numericId), Env.lng("PEER_DEADLINE_MS", 700));
        ctx.peers = peers;
        Faults faults = new Faults(ctx);
        Rollouts rollouts = new Rollouts(ctx);
        Snapshots snapshots = new Snapshots(ctx);
        LeaseRace leaseRace = new LeaseRace(ctx, peers);
        Replicator replicator = new Replicator(ctx, peers);
        Scenarios scenarios = new Scenarios(ctx, rollouts, peers, faults);
        AtomicReference<ApiServer> api = new AtomicReference<>();

        GrpcServices grpc = new GrpcServices(ctx,
                cmd -> handleCommand(ctx, faults, leaseRace, scenarios, api.get(), cmd),
                replicator,
                m -> onReport(ctx, snapshots, m));
        grpc.start(grpcPort);

        List<Integer> peerIds = new ArrayList<>(peers.targets().keySet());
        BullyElection election = new BullyElection(numericId, peerIds, peers, new BullyElection.Listener() {
            @Override
            public void onLeaderChange(Integer oldLeader, int newLeader, long epoch, String reason) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("previous", oldLeader == null ? null : "controller-" + oldLeader);
                d.put("leader", "controller-" + newLeader);
                d.put("epoch", epoch);
                d.put("reason", reason);
                ctx.events.record(newLeader == numericId ? "LEADER_ELECTED" : "LEADER_OBSERVED", "ok", null, d);
            }

            @Override
            public void onElectionStarted(String reason, long epoch) {
                ctx.events.record("ELECTION_STARTED", "ok", null, Map.of("reason", reason, "epoch", epoch));
            }
        }, new BullyElection.Timing(Env.lng("LEADER_HEARTBEAT_MS", 1000), Env.lng("LEADER_TIMEOUT_MS", 3500),
                Env.lng("COORDINATOR_WAIT_MS", 3000)), System::currentTimeMillis);
        ctx.election = election;

        ApiServer http = new ApiServer(ctx, rollouts, faults, peers, snapshots, new DeadlockDemo(ctx), leaseRace, scenarios);
        api.set(http);
        http.start(httpPort);
        ctx.events.record("CONTROLLER_STARTED", "ok", null, Map.of("version", version, "peers", peers.targets()));

        HealthMonitor health = new HealthMonitor(ctx);
        AtomicBoolean catchingUp = new AtomicBoolean();
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
        sched.scheduleWithFixedDelay(guard("election", election::tick), 500, 200, TimeUnit.MILLISECONDS);
        // A newly elected leader first adopts the freshest replica, then starts serving.
        sched.scheduleWithFixedDelay(guard("become-leader", () -> {
            long epoch = election.epoch();
            if (!election.isLeader() || ctx.readyEpoch == epoch || ctx.isolated()) return;
            if (!catchingUp.compareAndSet(false, true)) return;
            try {
                if (!replicator.catchUp()) return;
                ctx.leases.clear();
                ctx.clearOutboxes();
                store.seed();
                ctx.readyEpoch = epoch;
                Log.info("leader_ready", "epoch", epoch, "stateVersion", store.version());
            } finally {
                catchingUp.set(false);
            }
        }), 500, 200, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(guard("replicate", replicator::tick), 500, 200, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(guard("forward-events", () -> forwardEvents(ctx, peers)), 1000, 300, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(guard("persist", store::persistIfDirty), 1000, 1000, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(guard("leader-work", () -> {
            boolean leader = ctx.isLeader();
            if (leader) rollouts.schedulerTick();
            health.tick(leader);
        }), 2000, 1000, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(guard("sse-ping", ctx.sse::ping), 15, 15, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutdown", "node", id);
            sched.shutdownNow();
            store.persistIfDirty();
            http.stop();
            grpc.stop();
            election.shutdown();
            peers.shutdown();
        }));
        Log.info("controller_ready", "http", httpPort, "grpc", grpcPort, "peers", peers.targets());
        Thread.currentThread().join();
    }

    private static Runnable guard(String name, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                // A scheduled task that throws is cancelled silently, so every loop is guarded.
                Log.error("loop_failed", t, "loop", name);
            }
        };
    }

    // Events recorded on a follower are sent to the leader so they end up in the replicated log.
    private static void forwardEvents(Ctx ctx, PeerClient peers) {
        List<Msg> batch = ctx.events.drainPending();
        if (batch.isEmpty()) return;
        if (ctx.isLeader()) {
            for (Msg m : batch) ctx.store.addEvent(m);
            return;
        }
        Integer leader = ctx.leaderNumericId();
        if (leader == null || leader == ctx.numericId || ctx.isolated()) {
            ctx.events.requeue(batch);
            return;
        }
        try {
            peers.command(leader, "events", Map.of("events", batch), 2000);
        } catch (RuntimeException e) {
            ctx.events.requeue(batch);
        }
    }

    // Messages an agent reports to the leader: config acks, metrics and snapshot markers.
    static void onReport(Ctx ctx, Snapshots snapshots, Msg m) {
        ctx.clock.receive(m.lamport(), m.vclock());
        snapshots.onEvent(m);
        Map<String, Object> b = m.body() == null ? Map.of() : m.body();
        switch (m.type()) {
            case "METRICS" -> {
                if (!(b.get("rows") instanceof List<?> list)) return;
                int i = 0;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> row)) continue;
                    List<?> h = (List<?>) row.get("hist");
                    long[] hist = new long[h == null ? 0 : h.size()];
                    for (int k = 0; k < hist.length; k++) hist[k] = ((Number) h.get(k)).longValue();
                    ctx.store.addMetric(m.eventId() + ":" + (i++), m.node(), String.valueOf(row.get("flag")),
                            String.valueOf(b.get("env")), ((Number) b.get("revision")).longValue(), String.valueOf(row.get("variant")),
                            ((Number) row.get("requests")).longValue(), ((Number) row.get("errors")).longValue(), hist);
                }
            }
            case "CONFIG_APPLIED" -> {
                long rev = ((Number) b.get("revision")).longValue();
                ctx.store.write(st -> StateStore.ack(st, m.node(), rev));
                addRemote(ctx, m);
            }
            default -> addRemote(ctx, m);
        }
    }

    private static void addRemote(Ctx ctx, Msg m) {
        if (ctx.store.addEvent(m)) ctx.sse.broadcast(m.eventId(), "event", m);
        else ctx.events.duplicatesSuppressed.incrementAndGet();
    }

    static Rpc.PeerReply handleCommand(Ctx ctx, Faults faults, LeaseRace race, Scenarios scenarios, ApiServer api,
                                       Rpc.PeerCommand cmd) {
        Map<String, Object> a = cmd.args() == null ? Map.of() : cmd.args();
        // Fault commands always get through so a partition can be injected and lifted.
        if (!"fault".equals(cmd.command()) && ctx.isolated()) throw new IllegalStateException("simulated partition");
        return switch (cmd.command()) {
            case "fault" -> new Rpc.PeerReply(true, faults.controllerFault(a));
            case "lease-race" -> new Rpc.PeerReply(true, race.contend(String.valueOf(a.getOrDefault("name", ctx.id)),
                    ((Number) a.getOrDefault("startDelayMs", 0)).longValue(), 0, ((Number) a.getOrDefault("ttlMs", 1500)).longValue()));
            case "lease-op" -> {
                if (!ctx.isLeader()) throw new IllegalStateException("not leader");
                yield new Rpc.PeerReply(true, race.leaderOp(a));
            }
            case "status" -> new Rpc.PeerReply(true, Map.of("id", ctx.id, "leader", String.valueOf(ctx.leaderNumericId())));
            case "state-version" -> new Rpc.PeerReply(true, Map.of("epoch", ctx.store.epoch(), "version", ctx.store.version()));
            case "get-state" -> new Rpc.PeerReply(true, Map.of("state", ctx.store.toJson()));
            case "run-finish" -> {
                if (!ctx.isLeader()) throw new IllegalStateException("not leader");
                @SuppressWarnings("unchecked") Map<String, Object> res = (Map<String, Object>) a.get("result");
                scenarios.finishRun(((Number) a.get("id")).longValue(), String.valueOf(a.get("status")), res == null ? Map.of() : res);
                yield new Rpc.PeerReply(true, Map.of("ok", true));
            }
            case "events" -> {
                if (!ctx.isLeader()) throw new IllegalStateException("not leader");
                List<Msg> list = Json.M.convertValue(a.get("events"), new TypeReference<List<Msg>>() {});
                int added = 0;
                for (Msg m : list) {
                    ctx.clock.receive(m.lamport(), m.vclock());
                    if (ctx.store.addEvent(m)) added++;
                }
                yield new Rpc.PeerReply(true, Map.of("added", added));
            }
            case "api" -> {
                if (!ctx.isLeader() || api == null) throw new IllegalStateException("not leader");
                ApiServer.Resp r = api.handle(String.valueOf(a.get("method")), String.valueOf(a.get("path")),
                        String.valueOf(a.getOrDefault("query", "")), String.valueOf(a.getOrDefault("body", "")),
                        String.valueOf(a.getOrDefault("actor", "dashboard")));
                yield new Rpc.PeerReply(true, Map.of("status", r.status(), "body", Json.write(r.body())));
            }
            default -> throw new ApiServer.ApiError(400, "unknown command " + cmd.command());
        };
    }
}

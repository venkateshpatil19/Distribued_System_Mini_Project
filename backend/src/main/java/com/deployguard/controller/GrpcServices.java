package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Msg;
import com.deployguard.core.ConfigBundle;
import com.deployguard.rpc.Rpc;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

// gRPC server. Agents talk only to the leader: a follower answers "not leader" and names the leader.
// Peers use it for Bully election, state replication and commands.
public final class GrpcServices {
    public interface CommandHandler {
        Rpc.PeerReply handle(Rpc.PeerCommand cmd) throws Exception;
    }

    private final Ctx ctx;
    private final CommandHandler commands;
    private final Replicator replicator;
    private final Consumer<Msg> reportHandler;
    private Server server;

    public GrpcServices(Ctx ctx, CommandHandler commands, Replicator replicator, Consumer<Msg> reportHandler) {
        this.ctx = ctx;
        this.commands = commands;
        this.replicator = replicator;
        this.reportHandler = reportHandler;
    }

    public void start(int port) throws IOException {
        server = NettyServerBuilder.forPort(port)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .maxInboundMessageSize(64 * 1024 * 1024)
                .addService(controllerService())
                .addService(peerService())
                .build()
                .start();
        Log.info("grpc_started", "port", port);
    }

    public void stop() {
        if (server != null) server.shutdownNow();
    }

    private static <Q, R> ServerCalls.UnaryMethod<Q, R> unary(String name, Function<Q, R> f) {
        return (Q req, StreamObserver<R> obs) -> {
            try {
                obs.onNext(f.apply(req));
                obs.onCompleted();
            } catch (StatusLike s) {
                obs.onError(s.status.asRuntimeException());
            } catch (ApiServer.ApiError e) {
                obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            } catch (RuntimeException e) {
                Log.error("grpc_handler_failed", e, "method", name);
                obs.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage())).asRuntimeException());
            }
        };
    }

    // Lets handlers return a specific gRPC status.
    static final class StatusLike extends RuntimeException {
        final Status status;

        StatusLike(Status status) {
            super(status.getDescription());
            this.status = status;
        }
    }

    // ---------- agent-facing service (leader only) ----------

    private ServerServiceDefinition controllerService() {
        return ServerServiceDefinition.builder(Rpc.CONTROLLER_SERVICE)
                .addMethod(Rpc.REGISTER, ServerCalls.asyncUnaryCall(unary("Register", this::register)))
                .addMethod(Rpc.HEARTBEAT, ServerCalls.asyncUnaryCall(unary("Heartbeat", this::heartbeat)))
                .addMethod(Rpc.FETCH_CONFIG, ServerCalls.asyncUnaryCall(unary("FetchConfig", this::fetch)))
                .addMethod(Rpc.REPORT, ServerCalls.asyncUnaryCall(unary("Report", this::report)))
                .addMethod(Rpc.GET_TIME, ServerCalls.asyncUnaryCall(unary("GetTime", this::time)))
                .build();
    }

    private Rpc.RegisterResponse register(Rpc.RegisterRequest r) {
        if (r.agentId() == null || r.agentId().isBlank() || r.env() == null || r.env().isBlank()) {
            throw new ApiServer.ApiError(400, "agentId and env are required");
        }
        var st = ctx.clock.receive(r.lamport(), r.vclock());
        if (!ctx.isLeader()) {
            return new Rpc.RegisterResponse(false, redirectTo(), 0, ctx.id, ctx.fd.heartbeatMs(), st.lamport(), st.vclock());
        }
        ctx.store.write(s -> {
            StateStore.register(s, r, ctx.id, ctx.fd.suspectAfterMs());
            return null;
        });
        long desired = ctx.store.read(StateStore::latestRevision);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("agentId", r.agentId());
        d.put("env", r.env());
        d.put("incarnation", r.incarnation());
        d.put("appliedRevision", r.appliedRevision());
        d.put("via", ctx.id);
        ctx.events.record("AGENT_REGISTERED", "ok", desired, d);
        // Tell the new agent the current traffic setting; it only hears about later changes otherwise.
        ctx.sendControl(r.agentId(), "TRAFFIC", ctx.store.read(s -> new LinkedHashMap<>(s.traffic)));
        return new Rpc.RegisterResponse(true, ctx.numericId, desired, ctx.id, ctx.fd.heartbeatMs(), st.lamport(), st.vclock());
    }

    private Rpc.HeartbeatResponse heartbeat(Rpc.HeartbeatRequest h) {
        var st = ctx.clock.receive(h.lamport(), h.vclock());
        if (!ctx.isLeader()) {
            return new Rpc.HeartbeatResponse(false, true, redirectTo(), 0, ctx.id, st.lamport(), st.vclock(), List.of());
        }
        // A partitioned agent still polls for fault commands so the partition can be lifted,
        // but the poll does not count as a heartbeat: the failure detector should suspect it.
        if (h.activeFaults() != null && h.activeFaults().contains("partition")) {
            List<Msg> faultsOnly = ctx.drainCommands(h.agentId()).stream().filter(m -> "FAULT".equals(m.type())).toList();
            return new Rpc.HeartbeatResponse(true, true, ctx.numericId, 0, ctx.id, st.lamport(), st.vclock(), faultsOnly);
        }
        boolean known = ctx.store.write(s -> StateStore.heartbeat(s, h, ctx.id, ctx.fd.suspectAfterMs()));
        long desired = ctx.store.read(StateStore::latestRevision);
        List<Msg> cmds = known ? ctx.drainCommands(h.agentId()) : List.of();
        return new Rpc.HeartbeatResponse(true, known, ctx.numericId, desired, ctx.id, st.lamport(), st.vclock(), cmds);
    }

    private ConfigBundle fetch(Rpc.FetchConfigRequest r) {
        if (!ctx.isLeader()) throw new StatusLike(Status.FAILED_PRECONDITION.withDescription("not leader"));
        ConfigBundle b = ctx.store.read(s -> s.latestBundle);
        return b == null ? ConfigBundle.empty() : b;
    }

    // Acks, metrics and snapshot markers from one agent, in the order the agent produced them.
    private Rpc.ReportResponse report(Rpc.ReportRequest r) {
        if (!ctx.isLeader()) return new Rpc.ReportResponse(false, redirectTo(), 0);
        int n = 0;
        for (Msg m : r.messages() == null ? List.<Msg>of() : r.messages()) {
            reportHandler.accept(m);
            n++;
        }
        return new Rpc.ReportResponse(true, ctx.numericId, n);
    }

    private Rpc.TimeResponse time(Rpc.TimeRequest r) {
        // Cristian's algorithm: the server simply reports its clock; the client measures round-trip time.
        return new Rpc.TimeResponse(System.currentTimeMillis(), ctx.id);
    }

    // ---------- peer service ----------

    private ServerServiceDefinition peerService() {
        return ServerServiceDefinition.builder(Rpc.PEER_SERVICE)
                .addMethod(Rpc.ELECTION, ServerCalls.asyncUnaryCall(unary("Election", (Rpc.ElectionMsg m) -> {
                    peerGate(m.lamport(), m.vclock());
                    boolean ok = ctx.election.onElection(m.fromId(), m.epoch());
                    return new Rpc.Answer(ok, ctx.numericId, ctx.election.epoch());
                })))
                .addMethod(Rpc.COORDINATOR, ServerCalls.asyncUnaryCall(unary("Coordinator", (Rpc.CoordinatorMsg m) -> {
                    peerGate(m.lamport(), m.vclock());
                    ctx.election.onCoordinator(m.leaderId(), m.epoch());
                    return new Rpc.Answer(true, ctx.numericId, ctx.election.epoch());
                })))
                .addMethod(Rpc.LEADER_BEAT, ServerCalls.asyncUnaryCall(unary("LeaderHeartbeat", (Rpc.LeaderBeat m) -> {
                    peerGate(m.lamport(), m.vclock());
                    ctx.election.onHeartbeat(m.leaderId(), m.epoch());
                    return new Rpc.Answer(true, ctx.numericId, ctx.election.epoch());
                })))
                .addMethod(Rpc.REPLICATE, ServerCalls.asyncUnaryCall(unary("Replicate", (Rpc.ReplicateRequest m) -> {
                    peerGate(null, null);
                    return replicator.onReplicate(m);
                })))
                .addMethod(Rpc.COMMAND, ServerCalls.asyncUnaryCall(unary("Command", (Rpc.PeerCommand cmd) -> {
                    try {
                        return commands.handle(cmd);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                })))
                .build();
    }

    // Where a non-leader sends agents. Never ourselves: a cut-off old leader may still think it leads.
    private Integer redirectTo() {
        Integer l = ctx.leaderNumericId();
        return l == null || l == ctx.numericId ? null : l;
    }

    // An isolated controller (injected partition) refuses all peer traffic.
    private void peerGate(Long lamport, Map<String, Long> vclock) {
        if (ctx.isolated()) throw new StatusLike(Status.UNAVAILABLE.withDescription("simulated partition"));
        if (ctx.election == null) throw new StatusLike(Status.UNAVAILABLE.withDescription("starting"));
        if (lamport != null) ctx.clock.receive(lamport, vclock);
    }
}

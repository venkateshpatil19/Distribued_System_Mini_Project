package com.deployguard.controller;

import com.deployguard.core.BullyElection;
import com.deployguard.rpc.Rpc;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// gRPC client to the other controllers: Bully election transport, state replication and commands.
public final class PeerClient implements BullyElection.Transport {
    private final Ctx ctx;
    private final Map<Integer, String> targets;
    private final Map<Integer, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final long deadlineMs;

    // peers format: "1=controller-0.controller-peers:9090,2=controller-1.controller-peers:9090"
    public static Map<Integer, String> parsePeers(String spec, int selfId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq < 1) throw new IllegalStateException("Bad PEERS entry '" + p + "', expected id=host:port");
            int id = Integer.parseInt(p.substring(0, eq).trim());
            if (id != selfId) out.put(id, p.substring(eq + 1).trim());
        }
        return out;
    }

    public PeerClient(Ctx ctx, Map<Integer, String> targets, long deadlineMs) {
        this.ctx = ctx;
        this.targets = targets;
        this.deadlineMs = deadlineMs;
    }

    public Map<Integer, String> targets() {
        return targets;
    }

    private ManagedChannel channel(int peer) {
        return channels.computeIfAbsent(peer, p -> ManagedChannelBuilder.forTarget(targets.get(p))
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build());
    }

    private <Q, R> R call(int peer, MethodDescriptor<Q, R> m, Q req, long timeoutMs) {
        return ClientCalls.blockingUnaryCall(channel(peer), m, CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS), req);
    }

    @Override
    public boolean sendElection(int peer, long epoch) {
        if (ctx.isolated()) return false;
        var st = ctx.clock.tick();
        Rpc.Answer a = call(peer, Rpc.ELECTION, new Rpc.ElectionMsg(ctx.numericId, epoch, st.lamport(), st.vclock()), deadlineMs);
        return a.ok();
    }

    @Override
    public void sendCoordinator(int peer, long epoch) {
        if (ctx.isolated()) return;
        var st = ctx.clock.tick();
        call(peer, Rpc.COORDINATOR, new Rpc.CoordinatorMsg(ctx.numericId, epoch, st.lamport(), st.vclock()), deadlineMs);
    }

    @Override
    public long sendHeartbeat(int peer, long epoch) {
        if (ctx.isolated()) return -1;
        var st = ctx.clock.tick();
        return call(peer, Rpc.LEADER_BEAT, new Rpc.LeaderBeat(ctx.numericId, epoch, st.lamport(), st.vclock()), deadlineMs).epoch();
    }

    public Rpc.ReplicateResponse replicate(int peer, Rpc.ReplicateRequest req) {
        return call(peer, Rpc.REPLICATE, req, 2000);
    }

    public Rpc.PeerReply command(int peer, String command, Map<String, Object> args, long timeoutMs) {
        return call(peer, Rpc.COMMAND, new Rpc.PeerCommand(command, args), timeoutMs);
    }

    public void shutdown() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}

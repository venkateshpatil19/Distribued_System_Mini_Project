package com.deployguard.controller;

import com.deployguard.common.Msg;
import com.deployguard.common.NodeClock;
import com.deployguard.common.Retry;
import com.deployguard.core.BullyElection;
import com.deployguard.core.FailureDetector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Everything one controller process owns, passed to the services that need it.
public final class Ctx {
    // What the leader knows about each follower from its replication replies.
    public record PeerStatus(String id, int numericId, long lastAckMs, long epoch, Integer leaderView,
                             long version, long startedAtMs) {}

    public final String id;
    public final int numericId;
    public final String version;
    public final NodeClock clock;
    public final StateStore store;
    public final Leases leases;
    public final Sse sse;
    public final EventLog events;
    public final FailureDetector.Settings fd;
    public final long startedAtMs = System.currentTimeMillis();
    public volatile BullyElection election;
    public volatile PeerClient peers;
    // Fault injection: while isolatedUntil is in the future this controller ignores peer traffic.
    public volatile long isolatedUntilMs;
    // Epoch in which this node finished catching up as leader; until then it does not serve or replicate.
    public volatile long readyEpoch = -1;
    final Map<Integer, PeerStatus> peerStatus = new ConcurrentHashMap<>();
    // Commands waiting for each agent; delivered in its next heartbeat reply (a FIFO channel).
    private final Map<String, Deque<Msg>> outbox = new ConcurrentHashMap<>();

    public Ctx(String id, int numericId, String version, StateStore store, FailureDetector.Settings fd) {
        this.id = id;
        this.numericId = numericId;
        this.version = version;
        this.clock = new NodeClock(id);
        this.store = store;
        this.leases = new Leases(store);
        this.sse = new Sse();
        this.events = new EventLog(this);
        this.fd = fd;
    }

    public Integer leaderNumericId() {
        BullyElection e = election;
        return e == null ? null : e.leader();
    }

    public boolean isolated() {
        return System.currentTimeMillis() < isolatedUntilMs;
    }

    // A majority must have acknowledged our replication recently. A leader cut off from its peers
    // loses the majority, stops serving and lets agents move to the new leader.
    public boolean hasQuorum() {
        int peersTotal = peers == null ? 0 : peers.targets().size();
        if (peersTotal == 0) return true;
        if (isolated()) return false;
        long now = System.currentTimeMillis();
        int acks = 0;
        for (PeerStatus p : peerStatus.values()) if (now - p.lastAckMs() < 3000) acks++;
        return acks + 1 > (peersTotal + 1) / 2;
    }

    // Elected by Bully and still backed by a majority: only then do we accept writes and agents.
    public boolean isLeader() {
        BullyElection e = election;
        return e != null && e.isLeader() && readyEpoch == e.epoch() && hasQuorum();
    }

    public String holder(String purpose) {
        return id + "/" + purpose + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    // Tries to take a lease for up to waitMs, so short scheduler ticks do not fail user actions.
    public Optional<Leases.Lease> acquireWithin(String name, String holder, long ttlMs, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            Optional<Leases.Lease> l = leases.acquire(name, holder, ttlMs);
            if (l.isPresent() || System.currentTimeMillis() >= deadline) return l;
            Retry.sleepQuietly(100);
        }
    }

    public void releaseQuietly(Leases.Lease l) {
        if (l != null) leases.release(l);
    }

    // Queues a command for one agent, or for every known agent when agentId is null.
    public void sendControl(String agentId, String type, Map<String, Object> body) {
        List<String> targets = new ArrayList<>();
        if (agentId != null) targets.add(agentId);
        else targets.addAll(store.read(st -> new ArrayList<>(st.agents.keySet())));
        for (String a : targets) {
            Deque<Msg> q = outbox.computeIfAbsent(a, k -> new ArrayDeque<>());
            synchronized (q) {
                q.add(Msg.of(type, clock.tick(), body));
                while (q.size() > 200) q.poll();
            }
        }
    }

    public List<Msg> drainCommands(String agentId) {
        Deque<Msg> q = outbox.get(agentId);
        if (q == null) return List.of();
        synchronized (q) {
            List<Msg> out = new ArrayList<>(q);
            q.clear();
            return out;
        }
    }

    public void clearOutboxes() {
        outbox.clear();
    }
}

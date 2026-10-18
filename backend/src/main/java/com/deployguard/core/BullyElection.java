package com.deployguard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

// Bully leader election. The live process with the highest id becomes coordinator.
// The network sits behind Transport so the same logic runs over gRPC and in unit tests.
public final class BullyElection {

    public interface Transport {
        // True when the peer answered OK to our ELECTION message before the deadline.
        boolean sendElection(int peer, long epoch);

        void sendCoordinator(int peer, long epoch);

        // Returns the peer's current epoch, or -1 if the peer could not be reached.
        long sendHeartbeat(int peer, long epoch);
    }

    public interface Listener {
        void onLeaderChange(Integer oldLeader, int newLeader, long epoch, String reason);

        void onElectionStarted(String reason, long epoch);
    }

    public record Timing(long heartbeatMs, long leaderTimeoutMs, long coordinatorWaitMs) {
        public static Timing defaults() {
            return new Timing(1000, 3500, 3000);
        }
    }

    private final int selfId;
    private final List<Integer> peers;
    private final Transport transport;
    private final Listener listener;
    private final Timing timing;
    private final LongSupplier clock;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    private Integer leaderId;
    private long epoch;
    private long lastLeaderSignalMs;
    private long lastBeatSentMs;
    private boolean electing;
    private long okReceivedAtMs = -1;

    public BullyElection(int selfId, List<Integer> peers, Transport transport, Listener listener,
                         Timing timing, LongSupplier clock) {
        this.selfId = selfId;
        this.peers = List.copyOf(peers);
        this.transport = transport;
        this.listener = listener;
        this.timing = timing;
        this.clock = clock;
        this.lastLeaderSignalMs = clock.getAsLong();
    }

    public synchronized Integer leader() {
        return leaderId;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized boolean isLeader() {
        return leaderId != null && leaderId == selfId;
    }

    public synchronized boolean isElecting() {
        return electing;
    }

    public int selfId() {
        return selfId;
    }

    // Called periodically (every ~200 ms). Drives heartbeats, timeouts and election retries.
    public void tick() {
        long now = clock.getAsLong();
        boolean sendBeats = false;
        String startReason = null;
        synchronized (this) {
            if (isLeader()) {
                if (now - lastBeatSentMs >= timing.heartbeatMs()) {
                    lastBeatSentMs = now;
                    sendBeats = true;
                }
            } else if (electing) {
                // Someone higher said OK but never announced itself: it probably died mid-election.
                if (okReceivedAtMs >= 0 && now - okReceivedAtMs > timing.coordinatorWaitMs()) {
                    startReason = "no COORDINATOR after OK";
                }
            } else if (leaderId == null) {
                startReason = "no known leader";
            } else if (now - lastLeaderSignalMs > timing.leaderTimeoutMs()) {
                startReason = "leader " + leaderId + " heartbeat timeout";
            }
        }
        if (sendBeats) broadcastHeartbeats();
        if (startReason != null) startElection(startReason);
    }

    public void startElection(String reason) {
        List<Integer> higher = new ArrayList<>();
        long e;
        synchronized (this) {
            electing = true;
            okReceivedAtMs = -1;
            e = epoch;
            for (int p : peers) if (p > selfId) higher.add(p);
        }
        listener.onElectionStarted(reason, e);
        boolean anyOk = false;
        List<CompletableFuture<Boolean>> calls = new ArrayList<>();
        for (int p : higher) {
            calls.add(CompletableFuture.supplyAsync(() -> safe(() -> transport.sendElection(p, e)), pool));
        }
        for (CompletableFuture<Boolean> c : calls) {
            if (c.join()) anyOk = true;
        }
        if (anyOk) {
            synchronized (this) {
                okReceivedAtMs = clock.getAsLong();
            }
            return;
        }
        becomeLeader(reason);
    }

    private void becomeLeader(String reason) {
        Integer old;
        long newEpoch;
        synchronized (this) {
            old = leaderId;
            epoch = epoch + 1;
            newEpoch = epoch;
            leaderId = selfId;
            electing = false;
            okReceivedAtMs = -1;
            lastBeatSentMs = clock.getAsLong();
        }
        for (int p : peers) {
            pool.submit(() -> safe(() -> {
                transport.sendCoordinator(p, newEpoch);
                return true;
            }));
        }
        listener.onLeaderChange(old, selfId, newEpoch, reason);
    }

    private void broadcastHeartbeats() {
        long e = epoch();
        for (int p : peers) {
            pool.submit(() -> {
                long remote;
                try {
                    remote = transport.sendHeartbeat(p, e);
                } catch (Exception ex) {
                    return;
                }
                // A peer with a newer epoch means a leader was elected while we were cut off.
                // Re-run the election so our leadership (or theirs) is re-established under a newer epoch.
                boolean stale;
                synchronized (this) {
                    stale = remote > epoch && isLeader() && !electing;
                    if (stale) epoch = remote;
                }
                if (stale) startElection("peer " + p + " reports newer epoch " + remote);
            });
        }
    }

    // ELECTION from a lower id: answer OK, then run our own election (that is the "bully" step).
    public boolean onElection(int fromId, long remoteEpoch) {
        synchronized (this) {
            epoch = Math.max(epoch, remoteEpoch);
        }
        if (fromId < selfId) {
            boolean already;
            synchronized (this) {
                already = electing || isLeader();
            }
            if (!already) pool.submit(() -> startElection("ELECTION received from " + fromId));
            else if (isLeader()) pool.submit(() -> safe(() -> {
                transport.sendCoordinator(fromId, epoch());
                return true;
            }));
            return true;
        }
        return false;
    }

    public void onCoordinator(int newLeader, long remoteEpoch) {
        if (newLeader < selfId) {
            // A lower process claimed leadership while we are alive: bully it.
            synchronized (this) {
                epoch = Math.max(epoch, remoteEpoch);
            }
            pool.submit(() -> startElection("lower id " + newLeader + " announced itself"));
            return;
        }
        accept(newLeader, remoteEpoch, "COORDINATOR from " + newLeader);
    }

    public void onHeartbeat(int fromLeader, long remoteEpoch) {
        if (fromLeader < selfId) {
            // A lower id is acting as leader (for example we were partitioned away and it took over).
            // Even if we still think we lead, run a fresh election so the outcome gets a newer epoch.
            boolean busy;
            synchronized (this) {
                busy = electing;
                epoch = Math.max(epoch, remoteEpoch);
            }
            if (!busy) pool.submit(() -> startElection("heartbeat from lower id " + fromLeader));
            return;
        }
        boolean known;
        synchronized (this) {
            known = leaderId != null && leaderId == fromLeader;
            if (known) {
                lastLeaderSignalMs = clock.getAsLong();
                epoch = Math.max(epoch, remoteEpoch);
            }
        }
        if (!known) accept(fromLeader, remoteEpoch, "heartbeat from " + fromLeader);
    }

    private void accept(int newLeader, long remoteEpoch, String reason) {
        Integer old;
        boolean changed;
        synchronized (this) {
            old = leaderId;
            changed = old == null || old != newLeader;
            leaderId = newLeader;
            epoch = Math.max(epoch, remoteEpoch);
            electing = false;
            okReceivedAtMs = -1;
            lastLeaderSignalMs = clock.getAsLong();
        }
        if (changed) listener.onLeaderChange(old, newLeader, remoteEpoch, reason);
    }

    // Used by the "step down" fault: forget leadership so peers must elect again.
    public synchronized void forgetLeader() {
        leaderId = null;
        electing = false;
    }

    private static boolean safe(java.util.concurrent.Callable<Boolean> c) {
        try {
            return c.call();
        } catch (Exception e) {
            return false;
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}

package com.deployguard.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs several BullyElection instances over an in-memory network where nodes can crash or be cut off.
class BullyElectionTest {
    private final AtomicLong now = new AtomicLong(0);
    private final Map<Integer, BullyElection> nodes = new ConcurrentHashMap<>();
    private final Set<Integer> down = ConcurrentHashMap.newKeySet();
    private final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());

    private BullyElection.Transport net(int self) {
        return new BullyElection.Transport() {
            @Override
            public boolean sendElection(int peer, long epoch) {
                if (down.contains(self) || down.contains(peer)) return false;
                return nodes.get(peer).onElection(self, epoch);
            }

            @Override
            public void sendCoordinator(int peer, long epoch) {
                if (down.contains(self) || down.contains(peer)) return;
                nodes.get(peer).onCoordinator(self, epoch);
            }

            @Override
            public long sendHeartbeat(int peer, long epoch) {
                if (down.contains(self) || down.contains(peer)) return -1;
                nodes.get(peer).onHeartbeat(self, epoch);
                return nodes.get(peer).epoch();
            }
        };
    }

    private void start(int... ids) {
        for (int id : ids) {
            List<Integer> peers = new ArrayList<>();
            for (int p : ids) if (p != id) peers.add(p);
            nodes.put(id, new BullyElection(id, peers, net(id), new BullyElection.Listener() {
                @Override
                public void onLeaderChange(Integer oldLeader, int newLeader, long epoch, String reason) {
                    log.add(id + " sees leader " + newLeader + " epoch " + epoch);
                }

                @Override
                public void onElectionStarted(String reason, long epoch) {
                    log.add(id + " starts election: " + reason);
                }
            }, BullyElection.Timing.defaults(), now::get));
        }
    }

    // Advances simulated time in 200 ms steps and ticks every live node until the condition holds.
    private void runUntil(BooleanSupplier cond, long maxMs) throws InterruptedException {
        for (long t = 0; t < maxMs; t += 200) {
            now.addAndGet(200);
            for (Map.Entry<Integer, BullyElection> e : nodes.entrySet()) {
                if (!down.contains(e.getKey())) e.getValue().tick();
            }
            Thread.sleep(5);
            if (cond.getAsBoolean()) return;
        }
        throw new AssertionError("condition not met; log=" + log);
    }

    private boolean allAgree(int leader) {
        return nodes.entrySet().stream().filter(e -> !down.contains(e.getKey()))
                .allMatch(e -> Integer.valueOf(leader).equals(e.getValue().leader()));
    }

    @AfterEach
    void stop() {
        nodes.values().forEach(BullyElection::shutdown);
    }

    @Test
    void highestIdBecomesLeader() throws Exception {
        start(1, 2, 3);
        runUntil(() -> allAgree(3), 10_000);
        assertTrue(nodes.get(3).isLeader());
    }

    @Test
    void leaderCrashElectsNextHighestThenRecoveredNodeBulliesBack() throws Exception {
        start(1, 2, 3);
        runUntil(() -> allAgree(3), 10_000);
        long epochBefore = nodes.get(1).epoch();
        down.add(3);
        runUntil(() -> allAgree(2), 15_000);
        assertTrue(nodes.get(2).epoch() > epochBefore, "new leader must have a newer epoch");
        down.remove(3);
        runUntil(() -> allAgree(3), 15_000);
        assertEquals(3, nodes.get(1).leader());
    }

    @Test
    void partitionedLeaderReassertsWithNewerEpochAfterHealing() throws Exception {
        start(1, 2, 3);
        runUntil(() -> allAgree(3), 10_000);
        // Cut node 3 off without the others knowing; it keeps believing it leads.
        down.add(3);
        runUntil(() -> nodes.get(1).leader() != null && nodes.get(1).leader() == 2 && nodes.get(2).isLeader(), 15_000);
        long interimEpoch = nodes.get(2).epoch();
        assertTrue(nodes.get(3).isLeader(), "isolated node still thinks it leads (split brain until healed)");
        down.remove(3);
        runUntil(() -> allAgree(3) && nodes.get(3).epoch() > interimEpoch, 15_000);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("3 sees leader 3 epoch")));
    }

    @Test
    void twoFailuresLeaveLowestAsLeader() throws Exception {
        start(1, 2, 3, 4);
        runUntil(() -> allAgree(4), 10_000);
        down.add(4);
        down.add(3);
        runUntil(() -> allAgree(2), 20_000);
        down.add(2);
        runUntil(() -> allAgree(1), 20_000);
        assertTrue(nodes.get(1).isLeader());
    }
}

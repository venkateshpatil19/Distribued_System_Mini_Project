package com.deployguard.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClocksTest {

    @Test
    void lamportTicksAndTakesMaxOnReceive() {
        LamportClock c = new LamportClock();
        assertEquals(1, c.tick());
        assertEquals(2, c.tick());
        assertEquals(11, c.receive(10));
        // A smaller remote value still advances the local clock by one.
        assertEquals(12, c.receive(3));
    }

    @Test
    void lamportOrderRespectsSendReceive() {
        LamportClock p = new LamportClock();
        LamportClock q = new LamportClock();
        long send = p.tick();
        long recv = q.receive(send);
        assertTrue(send < recv);
    }

    @Test
    void vectorClockDetectsHappenedBeforeAndConcurrency() {
        VectorClock a = new VectorClock("A");
        VectorClock b = new VectorClock("B");
        Map<String, Long> a1 = a.tick();
        Map<String, Long> b1 = b.tick();
        assertEquals(VectorClock.Relation.CONCURRENT, VectorClock.compare(a1, b1));
        Map<String, Long> b2 = b.receive(a1);
        assertEquals(VectorClock.Relation.BEFORE, VectorClock.compare(a1, b2));
        assertEquals(VectorClock.Relation.AFTER, VectorClock.compare(b2, a1));
        assertEquals(VectorClock.Relation.EQUAL, VectorClock.compare(b2, b.snapshot()));
        assertEquals(Map.of("A", 1L, "B", 2L), b2);
    }

    @Test
    void missingEntriesCountAsZero() {
        assertEquals(VectorClock.Relation.BEFORE, VectorClock.compare(Map.of(), Map.of("X", 1L)));
        assertEquals(VectorClock.Relation.EQUAL, VectorClock.compare(null, Map.of()));
    }

    @Test
    void lamportCannotDistinguishConcurrentEventsButVectorCan() {
        NodeClock p = new NodeClock("P");
        NodeClock q = new NodeClock("Q");
        NodeClock.Stamp e1 = p.tick();
        q.tick();
        NodeClock.Stamp e2 = q.tick();
        // e1 has the smaller Lamport time, yet the events are causally unrelated.
        assertTrue(e1.lamport() < e2.lamport());
        assertEquals(VectorClock.Relation.CONCURRENT, VectorClock.compare(e1.vclock(), e2.vclock()));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertEquals(50, Retry.backoffMs(0, 100, 10_000, 0.0));
        assertEquals(100, Retry.backoffMs(0, 100, 10_000, 1.0));
        assertEquals(400, Retry.backoffMs(3, 100, 10_000, 0.0));
        assertEquals(5_000, Retry.backoffMs(20, 100, 10_000, 0.0));
        assertEquals(10_000, Retry.backoffMs(40, 100, 10_000, 1.0));
    }

    @Test
    void retrySucceedsAfterTransientFailuresAndGivesUpEventually() throws Exception {
        int[] calls = {0};
        String out = Retry.call("t", 4, 1, 2, () -> {
            if (++calls[0] < 3) throw new IllegalStateException("transient");
            return "ok";
        });
        assertEquals("ok", out);
        assertEquals(3, calls[0]);
        int[] calls2 = {0};
        try {
            Retry.call("t", 2, 1, 2, () -> {
                calls2[0]++;
                throw new IllegalStateException("always");
            });
        } catch (IllegalStateException e) {
            assertEquals("always", e.getMessage());
        }
        assertEquals(2, calls2[0]);
    }
}

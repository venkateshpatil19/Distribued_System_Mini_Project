package com.deployguard.core;

import com.deployguard.core.RolloutState.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutLogicTest {

    @Test
    void validTransitions() {
        assertEquals(RolloutState.RUNNING, RolloutState.RUNNING.apply(Action.PROMOTE, false));
        assertEquals(RolloutState.COMPLETED, RolloutState.RUNNING.apply(Action.PROMOTE, true));
        assertEquals(RolloutState.PAUSED, RolloutState.RUNNING.apply(Action.PAUSE, false));
        assertEquals(RolloutState.RUNNING, RolloutState.PAUSED.apply(Action.RESUME, false));
        assertEquals(RolloutState.ROLLED_BACK, RolloutState.PAUSED.apply(Action.ROLLBACK, false));
        assertEquals(RolloutState.ABORTED, RolloutState.RUNNING.apply(Action.ABORT, false));
        // Manual override: promoting a paused rollout continues it.
        assertEquals(RolloutState.RUNNING, RolloutState.PAUSED.apply(Action.PROMOTE, false));
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertThrows(IllegalStateException.class, () -> RolloutState.PAUSED.apply(Action.PAUSE, false));
        assertThrows(IllegalStateException.class, () -> RolloutState.RUNNING.apply(Action.RESUME, false));
        for (RolloutState terminal : new RolloutState[]{RolloutState.COMPLETED, RolloutState.ROLLED_BACK, RolloutState.ABORTED}) {
            assertTrue(terminal.isTerminal());
            for (Action a : Action.values()) {
                assertThrows(IllegalStateException.class, () -> terminal.apply(a, false), terminal + " " + a);
            }
        }
        assertFalse(RolloutState.RUNNING.isTerminal());
    }

    @Test
    void guardrailsNeedEnoughDataThenCheckErrorsBeforeLatency() {
        Guardrails.Policy p = new Guardrails.Policy(0.05, 250, 100);
        assertEquals(Guardrails.Verdict.INSUFFICIENT_DATA, Guardrails.evaluate(p, new Guardrails.Stats(99, 90, 900)).verdict());
        assertEquals(Guardrails.Verdict.BREACH_ERROR_RATE, Guardrails.evaluate(p, new Guardrails.Stats(100, 6, 900)).verdict());
        assertEquals(Guardrails.Verdict.BREACH_LATENCY, Guardrails.evaluate(p, new Guardrails.Stats(100, 5, 251)).verdict());
        // Exactly at the limits is still healthy.
        assertEquals(Guardrails.Verdict.HEALTHY, Guardrails.evaluate(p, new Guardrails.Stats(100, 5, 250)).verdict());
        assertEquals(0.0, new Guardrails.Stats(0, 0, 0).errorRate());
    }

    @Test
    void guardrailPolicyValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Guardrails.Policy(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Guardrails.Policy(0.1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Guardrails.Policy(0.1, 1, 0));
    }

    @Test
    void mergedHistogramGivesBucketP95() {
        Histogram a = new Histogram();
        Histogram b = new Histogram();
        for (int i = 0; i < 95; i++) a.record(20);
        for (int i = 0; i < 5; i++) b.record(600);
        assertEquals(25, a.quantileUpperBoundMs(0.95));
        Histogram merged = new Histogram();
        merged.merge(a.counts());
        merged.merge(b.counts());
        assertEquals(100, merged.total());
        assertEquals(25, merged.quantileUpperBoundMs(0.95));
        b.record(600);
        merged.merge(new long[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0});
        // 6 slow requests out of 101 push the 95th percentile into the 750 ms bucket.
        assertEquals(750, merged.quantileUpperBoundMs(0.95));
        assertEquals(-1, new Histogram().quantileUpperBoundMs(0.95));
        Histogram over = new Histogram();
        over.record(99_999);
        assertEquals(5000, over.quantileUpperBoundMs(0.5));
    }

    @Test
    void failureDetectorStates() {
        FailureDetector.Settings s = FailureDetector.Settings.defaults();
        assertEquals(FailureDetector.Health.HEALTHY, FailureDetector.classify(s, 500, 0, -1));
        assertEquals(FailureDetector.Health.DELAYED, FailureDetector.classify(s, 2500, 0, -1));
        assertEquals(FailureDetector.Health.SUSPECTED, FailureDetector.classify(s, 3500, 0, -1));
        assertEquals(FailureDetector.Health.DISCONNECTED, FailureDetector.classify(s, 9000, 0, -1));
        assertEquals(FailureDetector.Health.STALE, FailureDetector.classify(s, 500, 5000, -1));
        assertEquals(FailureDetector.Health.RECOVERING, FailureDetector.classify(s, 500, 0, 2000));
        assertEquals(FailureDetector.Health.HEALTHY, FailureDetector.classify(s, 500, 0, 20_000));
        assertTrue(FailureDetector.isLive(FailureDetector.Health.STALE));
        assertFalse(FailureDetector.isLive(FailureDetector.Health.SUSPECTED));
        assertThrows(IllegalArgumentException.class, () -> new FailureDetector.Settings(1000, 500, 8000, 1, 1));
    }
}

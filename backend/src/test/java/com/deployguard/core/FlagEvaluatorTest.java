package com.deployguard.core;

import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.ConfigBundle.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagEvaluatorTest {

    private static FlagEnvConfig cfg(boolean enabled, int pct, Rule... rules) {
        return new FlagEnvConfig(enabled, List.of(rules), pct, "true", "false");
    }

    @Test
    void disabledFlagServesOff() {
        var e = FlagEvaluator.evaluate("f", cfg(false, 100), Map.of("userId", "u1"));
        assertEquals("off", e.variant());
        assertEquals("FLAG_DISABLED", e.reason());
    }

    @Test
    void firstMatchingRuleWins() {
        var c = cfg(true, 0,
                new Rule("country", "in", List.of("IN", "US"), "on"),
                new Rule("plan", "equals", List.of("pro"), "off"));
        var e = FlagEvaluator.evaluate("f", c, Map.of("userId", "u1", "country", "IN", "plan", "pro"));
        assertEquals("on", e.variant());
        assertEquals(0, e.ruleIndex());
        var e2 = FlagEvaluator.evaluate("f", c, Map.of("userId", "u1", "country", "DE", "plan", "pro"));
        assertEquals("off", e2.variant());
        assertEquals(1, e2.ruleIndex());
    }

    @Test
    void operatorsBehave() {
        Map<String, String> ctx = Map.of("email", "a@corp.com", "age", "30");
        assertTrue(FlagEvaluator.matches(new Rule("email", "contains", List.of("@corp"), "on"), ctx));
        assertTrue(FlagEvaluator.matches(new Rule("email", "starts_with", List.of("a@"), "on"), ctx));
        assertTrue(FlagEvaluator.matches(new Rule("age", "gt", List.of("18"), "on"), ctx));
        assertTrue(FlagEvaluator.matches(new Rule("age", "lt", List.of("31"), "on"), ctx));
        assertTrue(FlagEvaluator.matches(new Rule("age", "not_in", List.of("1", "2"), "on"), ctx));
        // Non-numeric comparison never matches.
        assertTrue(!FlagEvaluator.matches(new Rule("email", "gt", List.of("1"), "on"), ctx));
        // A missing attribute satisfies only negative operators.
        assertTrue(FlagEvaluator.matches(new Rule("missing", "not_equals", List.of("x"), "on"), ctx));
        assertTrue(!FlagEvaluator.matches(new Rule("missing", "equals", List.of("x"), "on"), ctx));
    }

    @Test
    void bucketsAreStableAndInRange() {
        for (int i = 0; i < 1000; i++) {
            int b = FlagEvaluator.bucket("flag", "user-" + i);
            assertTrue(b >= 0 && b < FlagEvaluator.BUCKETS);
            assertEquals(b, FlagEvaluator.bucket("flag", "user-" + i));
        }
    }

    @Test
    void raisingPercentageNeverTurnsUsersOff() {
        for (int i = 0; i < 2000; i++) {
            Map<String, String> u = Map.of("userId", "u" + i);
            boolean at5 = "on".equals(FlagEvaluator.evaluate("f", cfg(true, 5), u).variant());
            boolean at25 = "on".equals(FlagEvaluator.evaluate("f", cfg(true, 25), u).variant());
            boolean at50 = "on".equals(FlagEvaluator.evaluate("f", cfg(true, 50), u).variant());
            if (at5) assertTrue(at25);
            if (at25) assertTrue(at50);
        }
    }

    @Test
    void percentageIsApproximatelyRespected() {
        int on = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            if ("on".equals(FlagEvaluator.evaluate("checkout", cfg(true, 25), Map.of("userId", "u" + i)).variant())) on++;
        }
        double pct = on * 100.0 / n;
        assertTrue(pct > 23.5 && pct < 26.5, "observed " + pct + "%");
    }

    @Test
    void percentageNeedsUserId() {
        var e = FlagEvaluator.evaluate("f", cfg(true, 100), Map.of());
        assertEquals("off", e.variant());
        assertTrue(e.reason().startsWith("NO_USER_ID"));
    }

    @Test
    void valueValidationMatchesType() {
        assertNull(FlagEvaluator.validateValue("boolean", "true"));
        assertNotNull(FlagEvaluator.validateValue("boolean", "\"true\""));
        assertNull(FlagEvaluator.validateValue("string", "\"x\""));
        assertNotNull(FlagEvaluator.validateValue("string", "x"));
        assertNull(FlagEvaluator.validateValue("number", "12.5"));
        assertNull(FlagEvaluator.validateValue("json", "{\"a\":1}"));
        assertNotNull(FlagEvaluator.validateValue("json", "3"));
        assertNotNull(FlagEvaluator.validateValue("date", "3"));
        assertNotNull(FlagEvaluator.validateRule(new Rule("x", "regex", List.of("a"), "on")));
        assertNotNull(FlagEvaluator.validateRule(new Rule("x", "equals", List.of(), "on")));
        assertNull(FlagEvaluator.validateRule(new Rule("x", "equals", List.of("a"), "off")));
    }

    @Test
    void configRejectsBadPercent() {
        assertThrows(IllegalArgumentException.class, () -> cfg(true, 101));
        assertThrows(IllegalArgumentException.class, () -> cfg(true, -1));
    }
}

package com.deployguard.core;

import com.deployguard.common.Json;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.ConfigBundle.Rule;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Evaluates a flag for one request context and explains the decision.
public final class FlagEvaluator {
    public static final Set<String> TYPES = Set.of("boolean", "string", "number", "json");
    public static final Set<String> OPERATORS = Set.of("equals", "not_equals", "in", "not_in", "contains",
            "starts_with", "gt", "lt");
    public static final int BUCKETS = 10_000;

    public record Evaluation(String variant, String value, String reason, int bucket, Integer ruleIndex) {}

    private FlagEvaluator() {}

    public static Evaluation evaluate(String flagKey, FlagEnvConfig cfg, Map<String, String> ctx) {
        String unit = ctx == null ? null : ctx.get("userId");
        int bucket = unit == null || unit.isBlank() ? -1 : bucket(flagKey, unit);
        if (cfg == null) {
            return new Evaluation("off", "null", "FLAG_NOT_CONFIGURED", bucket, null);
        }
        if (!cfg.enabled()) {
            return new Evaluation("off", cfg.offValue(), "FLAG_DISABLED", bucket, null);
        }
        List<Rule> rules = cfg.rules();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (matches(r, ctx)) {
                boolean on = "on".equals(r.serve());
                return new Evaluation(on ? "on" : "off", on ? cfg.onValue() : cfg.offValue(),
                        "RULE_MATCH: rule " + (i + 1) + " (" + r.attribute() + " " + r.operator() + " " + r.values() + ")",
                        bucket, i);
            }
        }
        if (bucket < 0) {
            // Without a stable unit id we cannot place the request in a percentage bucket.
            return new Evaluation("off", cfg.offValue(), "NO_USER_ID: percentage rollout needs userId", bucket, null);
        }
        int threshold = cfg.rolloutPercent() * (BUCKETS / 100);
        boolean on = bucket < threshold;
        return new Evaluation(on ? "on" : "off", on ? cfg.onValue() : cfg.offValue(),
                "PERCENTAGE: bucket " + bucket + (on ? " < " : " >= ") + threshold + " (" + cfg.rolloutPercent() + "%)",
                bucket, null);
    }

    static boolean matches(Rule r, Map<String, String> ctx) {
        if (ctx == null || r.attribute() == null) return false;
        String actual = ctx.get(r.attribute());
        List<String> vals = r.values();
        String op = r.operator() == null ? "" : r.operator();
        if (actual == null) return op.equals("not_equals") || op.equals("not_in");
        String first = vals.isEmpty() ? "" : vals.get(0);
        return switch (op) {
            case "equals" -> actual.equals(first);
            case "not_equals" -> !actual.equals(first);
            case "in" -> vals.contains(actual);
            case "not_in" -> !vals.contains(actual);
            case "contains" -> actual.contains(first);
            case "starts_with" -> actual.startsWith(first);
            case "gt" -> compareNum(actual, first) > 0;
            case "lt" -> compareNum(actual, first) < 0;
            default -> false;
        };
    }

    private static int compareNum(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            // Non-numeric values never satisfy gt/lt; returning 0 makes both comparisons false.
            return 0;
        }
    }

    // Stable bucket in [0, 10000): the same flag and user always land in the same bucket,
    // so raising the percentage only adds users and never flips existing ones off.
    public static int bucket(String flagKey, String unitId) {
        byte[] data = (flagKey + ":" + unitId).getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (byte b : data) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        // MurmurHash3 finalizer improves the avalanche of plain FNV-1a.
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return (int) (Integer.toUnsignedLong(h) % BUCKETS);
    }

    // Checks that a JSON-encoded value matches the declared flag type. Returns an error message or null.
    public static String validateValue(String type, String jsonValue) {
        if (!TYPES.contains(type)) return "type must be one of " + TYPES;
        if (jsonValue == null) return "value is required";
        JsonNode n;
        try {
            n = Json.tree(jsonValue);
        } catch (IllegalArgumentException e) {
            return "value is not valid JSON";
        }
        return switch (type) {
            case "boolean" -> n.isBoolean() ? null : "value must be true or false";
            case "string" -> n.isTextual() ? null : "value must be a JSON string, for example \"blue\"";
            case "number" -> n.isNumber() ? null : "value must be a number";
            default -> (n.isObject() || n.isArray()) ? null : "value must be a JSON object or array";
        };
    }

    public static String validateRule(Rule r) {
        if (r.attribute() == null || r.attribute().isBlank()) return "rule attribute is required";
        if (!OPERATORS.contains(r.operator())) return "rule operator must be one of " + OPERATORS;
        if (r.values().isEmpty()) return "rule needs at least one value";
        if (!"on".equals(r.serve()) && !"off".equals(r.serve())) return "rule serve must be 'on' or 'off'";
        return null;
    }
}

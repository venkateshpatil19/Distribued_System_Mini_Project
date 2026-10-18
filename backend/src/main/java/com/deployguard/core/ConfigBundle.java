package com.deployguard.core;

import java.util.List;
import java.util.Map;

// Full, versioned configuration snapshot pushed to agents. A higher revision always supersedes a lower one,
// so agents can ignore delayed or duplicated messages safely.
public record ConfigBundle(long revision, long createdAtMs, String reason, Map<String, FlagSnapshot> flags) {

    public record FlagSnapshot(String key, String type, Map<String, FlagEnvConfig> envs) {}

    public record FlagEnvConfig(boolean enabled, List<Rule> rules, int rolloutPercent,
                                String onValue, String offValue) {
        public FlagEnvConfig {
            rules = rules == null ? List.of() : List.copyOf(rules);
            if (rolloutPercent < 0 || rolloutPercent > 100) {
                throw new IllegalArgumentException("rolloutPercent must be between 0 and 100");
            }
        }

        public FlagEnvConfig withPercent(int p) {
            return new FlagEnvConfig(enabled, rules, p, onValue, offValue);
        }

        public FlagEnvConfig withEnabled(boolean e) {
            return new FlagEnvConfig(e, rules, rolloutPercent, onValue, offValue);
        }
    }

    // A targeting rule: if context[attribute] matches, serve the "on" or "off" variant.
    public record Rule(String attribute, String operator, List<String> values, String serve) {
        public Rule {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public static ConfigBundle empty() {
        return new ConfigBundle(0, 0, "empty", Map.of());
    }
}

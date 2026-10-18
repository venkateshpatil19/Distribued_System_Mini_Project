package com.deployguard.core;

// Health guardrails for the treatment group ("on" variant) of a rollout stage.
public final class Guardrails {
    public enum Verdict { HEALTHY, INSUFFICIENT_DATA, BREACH_ERROR_RATE, BREACH_LATENCY }

    public record Policy(double maxErrorRate, long maxP95Ms, long minRequests) {
        public Policy {
            if (maxErrorRate <= 0 || maxErrorRate > 1) throw new IllegalArgumentException("maxErrorRate must be in (0, 1]");
            if (maxP95Ms <= 0) throw new IllegalArgumentException("maxP95Ms must be positive");
            if (minRequests < 1) throw new IllegalArgumentException("minRequests must be at least 1");
        }
    }

    public record Stats(long requests, long errors, long p95Ms) {
        public double errorRate() {
            return requests == 0 ? 0 : (double) errors / requests;
        }
    }

    public record Result(Verdict verdict, String detail) {}

    private Guardrails() {}

    public static Result evaluate(Policy p, Stats s) {
        if (s.requests() < p.minRequests()) {
            return new Result(Verdict.INSUFFICIENT_DATA,
                    "only " + s.requests() + " of " + p.minRequests() + " required requests observed");
        }
        // Error rate is checked first because errors are a stronger signal than slowness.
        if (s.errorRate() > p.maxErrorRate()) {
            return new Result(Verdict.BREACH_ERROR_RATE, String.format(
                    "error rate %.2f%% exceeded limit %.2f%% (%d errors / %d requests)",
                    s.errorRate() * 100, p.maxErrorRate() * 100, s.errors(), s.requests()));
        }
        if (s.p95Ms() > p.maxP95Ms()) {
            return new Result(Verdict.BREACH_LATENCY,
                    "p95 latency " + s.p95Ms() + " ms exceeded limit " + p.maxP95Ms() + " ms");
        }
        return new Result(Verdict.HEALTHY, String.format("error rate %.2f%%, p95 %d ms over %d requests",
                s.errorRate() * 100, s.p95Ms(), s.requests()));
    }
}

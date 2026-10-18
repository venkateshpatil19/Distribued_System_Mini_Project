package com.deployguard.common;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

// Bounded retries with exponential backoff and jitter.
public final class Retry {
    private Retry() {}

    // Delay for a 0-based attempt: half fixed, half random, so retrying nodes do not stampede together.
    public static long backoffMs(int attempt, long baseMs, long maxMs, double random01) {
        double exp = baseMs * Math.pow(2, Math.min(attempt, 30));
        long capped = (long) Math.min(maxMs, exp);
        return capped / 2 + (long) (random01 * (capped / 2.0));
    }

    public static <T> T call(String what, int maxAttempts, long baseMs, long maxMs, Callable<T> op) throws Exception {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return op.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt + 1 >= maxAttempts) break;
                long d = backoffMs(attempt, baseMs, maxMs, ThreadLocalRandom.current().nextDouble());
                Log.warn("retry", "op", what, "attempt", attempt + 1, "delayMs", d, "error", String.valueOf(e.getMessage()));
                Thread.sleep(d);
            }
        }
        throw last;
    }

    public static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

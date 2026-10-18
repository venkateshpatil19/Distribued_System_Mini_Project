package com.deployguard.core;

// Beacon-based failure detector. A missed heartbeat only raises suspicion: the node may be slow,
// partitioned or crashed, and this detector cannot tell which.
public final class FailureDetector {
    public enum Health { HEALTHY, DELAYED, SUSPECTED, DISCONNECTED, STALE, RECOVERING }

    public record Settings(long heartbeatMs, long suspectAfterMs, long disconnectAfterMs, long staleAfterMs,
                           long recoveringForMs) {
        public Settings {
            if (heartbeatMs <= 0 || suspectAfterMs <= heartbeatMs || disconnectAfterMs <= suspectAfterMs) {
                throw new IllegalArgumentException("need 0 < heartbeat < suspectAfter < disconnectAfter");
            }
        }

        public static Settings defaults() {
            return new Settings(1000, 3000, 8000, 4000, 10000);
        }
    }

    private FailureDetector() {}

    // ageMs: time since last heartbeat. staleForMs: how long the agent has been behind the desired revision
    // (0 when up to date). sinceRecoveryMs: time since it came back after a failure, or -1 if it never failed.
    public static Health classify(Settings s, long ageMs, long staleForMs, long sinceRecoveryMs) {
        if (ageMs > s.disconnectAfterMs()) return Health.DISCONNECTED;
        if (ageMs > s.suspectAfterMs()) return Health.SUSPECTED;
        if (ageMs > 2 * s.heartbeatMs()) return Health.DELAYED;
        if (staleForMs > s.staleAfterMs()) return Health.STALE;
        if (sinceRecoveryMs >= 0 && sinceRecoveryMs < s.recoveringForMs()) return Health.RECOVERING;
        return Health.HEALTHY;
    }

    public static boolean isLive(Health h) {
        return h == Health.HEALTHY || h == Health.DELAYED || h == Health.STALE || h == Health.RECOVERING;
    }
}

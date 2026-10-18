package com.deployguard.common;

// Lamport logical clock: tick before local events and sends, max(local, remote) + 1 on receive.
public final class LamportClock {
    private long time;

    public LamportClock() {
        this(0);
    }

    public LamportClock(long start) {
        this.time = start;
    }

    public synchronized long tick() {
        return ++time;
    }

    public synchronized long receive(long remote) {
        time = Math.max(time, remote) + 1;
        return time;
    }

    public synchronized long get() {
        return time;
    }
}

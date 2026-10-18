package com.deployguard.controller;

import com.deployguard.common.Log;
import com.deployguard.common.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// Records consequential events with Lamport and vector clocks. The leader stores them in the replicated
// state; a follower keeps them in an outbox and forwards them to the leader.
public final class EventLog {
    private final Ctx ctx;
    private final List<Msg> pending = new ArrayList<>();
    final AtomicLong duplicatesSuppressed = new AtomicLong();

    EventLog(Ctx ctx) {
        this.ctx = ctx;
    }

    public Msg record(String type, String outcome, Long revision, Map<String, Object> data) {
        return record(type, outcome, revision, data, false);
    }

    public Msg record(String type, String outcome, Long revision, Map<String, Object> data, boolean simulated) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        if (revision != null) body.put("revision", revision);
        body.put("simulated", simulated);
        if (data != null) body.putAll(data);
        Msg m = Msg.of(type, ctx.clock.tick(), body);
        store(m);
        Log.info("event", "type", type, "outcome", outcome, "lamport", m.lamport());
        return m;
    }

    // Leader: store and stream. Follower: stream locally and queue for the leader.
    void store(Msg m) {
        if (ctx.isLeader()) {
            if (ctx.store.addEvent(m)) ctx.sse.broadcast(m.eventId(), "event", m);
            else duplicatesSuppressed.incrementAndGet();
        } else {
            ctx.sse.broadcast(m.eventId(), "event", m);
            synchronized (pending) {
                pending.add(m);
                if (pending.size() > 500) pending.remove(0);
            }
        }
    }

    List<Msg> drainPending() {
        synchronized (pending) {
            List<Msg> out = new ArrayList<>(pending);
            pending.clear();
            return out;
        }
    }

    void requeue(List<Msg> msgs) {
        synchronized (pending) {
            pending.addAll(0, msgs);
        }
    }
}

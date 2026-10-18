package com.deployguard.common;

import java.util.Map;
import java.util.UUID;

// Message envelope used between agents and controllers. It carries the sender's logical clocks.
public record Msg(String type, String eventId, String node, long lamport, Map<String, Long> vclock,
                  long wallTs, Map<String, Object> body) {

    public static Msg of(String type, NodeClock.Stamp st, Map<String, Object> body) {
        return of(type, st, body, System.currentTimeMillis());
    }

    // wallTs lets an agent stamp messages with its own (possibly skewed) physical clock.
    public static Msg of(String type, NodeClock.Stamp st, Map<String, Object> body, long wallTs) {
        return new Msg(type, UUID.randomUUID().toString(), st.node(), st.lamport(), st.vclock(),
                wallTs, body == null ? Map.of() : body);
    }
}

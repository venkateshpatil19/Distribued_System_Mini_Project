package com.deployguard.common;

import java.util.Map;

// Lamport and vector clock owned by one node. Every send and receive goes through here.
public final class NodeClock {
    public record Stamp(String node, long lamport, Map<String, Long> vclock) {}

    private final String nodeId;
    private final LamportClock lamport = new LamportClock();
    private final VectorClock vector;

    public NodeClock(String nodeId) {
        this.nodeId = nodeId;
        this.vector = new VectorClock(nodeId);
    }

    public String nodeId() {
        return nodeId;
    }

    // Local event or message send.
    public synchronized Stamp tick() {
        long l = lamport.tick();
        return new Stamp(nodeId, l, vector.tick());
    }

    // Message receive; a null remote stamp is treated as a local event.
    public synchronized Stamp receive(Long remoteLamport, Map<String, Long> remoteVector) {
        long l = remoteLamport == null ? lamport.tick() : lamport.receive(remoteLamport);
        return new Stamp(nodeId, l, vector.receive(remoteVector));
    }

    public long lamport() {
        return lamport.get();
    }

    public Map<String, Long> vector() {
        return vector.snapshot();
    }
}

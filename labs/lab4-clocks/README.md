# Lab 4: Logical clocks (Lamport, vector) and clock synchronization

**Aim:** demonstrate Lamport and vector clocks and show how they order events.

**Real-world link:** every DeployGuard message and stored event carries a Lamport time and a vector clock (`backend/.../common/NodeClock.java`). The dashboard's Experiments page can compare any two events, and agents run Cristian's algorithm against the controllers.

## Files
- `LogicalClocks.java`: three processes (threads with message queues) act out a controller pushing a config revision to two agents that acknowledge it. Every event prints its Lamport and vector time, then every pair of events from different processes is classified as `->` (happened before) or `||` (concurrent).
- `Berkeley.java`: Berkeley synchronization. The master polls each node, corrects for half the round-trip time, ignores a faulty clock (+5000 ms), averages the rest and sends each node a relative adjustment.

## Run
```bash
javac LogicalClocks.java Berkeley.java
java LogicalClocks
java Berkeley
```

## Rules
- Lamport: increment before each local or send event; on receive, `L = max(L, L_msg) + 1`.
- Vector: increment your own entry before each event; on receive, take the element-wise max, then increment your own entry.
- `a -> b` implies `L(a) < L(b)`, but the converse is false. With vectors, `a -> b` holds if and only if `V(a) < V(b)`.

## Recorded output (excerpt)
```
P1  a    Lamport=1   vector=[1, 0, 0]
P1  b    Lamport=2   vector=[2, 0, 0]      b sends to P2
P2  g    Lamport=3   vector=[2, 2, 0]      g receives b
P2  h    Lamport=4   vector=[2, 3, 0]      h sends to P1
P1  d    Lamport=5   vector=[4, 3, 0]      d receives h
P3  m    Lamport=5   vector=[3, 0, 4]
  b -> g
  h -> d
  d || m  (concurrent)       same Lamport time 5, no causal link
  k || c  (concurrent)       Lamport 2 < 3, yet still not causal
Concurrent pairs: 38, of which 29 have different Lamport times.
```

Berkeley:
```
  poll agent-4  rtt 14 ms -> differs from master by +5000 ms  (ignored: beyond 1000 ms)
Average difference of 4 good clocks: 30 ms
After sync: every clock at +30 ms
```

## Limits worth stating in the viva
- Berkeley and Cristian assume roughly symmetric network delay; their error is bounded by about RTT/2.
- Berkeley synchronizes clocks with each other (internal synchronization), not with UTC.
- Vector clocks grow with the number of processes; DeployGuard keys them by node id so new agents simply add an entry.

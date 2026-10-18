# Lab 3: Deadlock detection with a wait-for graph

**Aim:** detect deadlock in a distributed system using a wait-for graph (WFG).

**Real-world link:** DeployGuard workflows lock feature flags with leases. Its controller builds the global WFG from the wait edges recorded on the leader and aborts the youngest workflow (`backend/.../controller/DeadlockDemo.java`, `core/WaitForGraph.java`, and the Experiments page in the dashboard).

## Files
`DeadlockDetection.java` has two parts:
- **Part A, centralized WFG:** builds the graph from a resource-allocation table (`Wi -> Wj` when Wi waits for a resource Wj holds), finds a cycle with three-colour DFS in O(V + E), aborts the youngest process in the cycle, hands released resources to waiters and checks again.
- **Part B, Chandy-Misra-Haas edge chasing:** three sites run as threads with message queues. A blocked process sends `probe(initiator, sender, receiver)` along its dependency edges; blocked processes forward it. If the initiator gets its own probe back, it is deadlocked.

## Run
```bash
javac DeadlockDetection.java
java DeadlockDetection
```

## Recorded output
```
Wait-for graph: {W1=[W2], W2=[W3], W3=[W1], W4=[W3]}
DEADLOCK cycle [W1, W2, W3] -> abort youngest W3 (start time 30)
  W2 now acquires flag:search
Wait-for graph: {W1=[W2], W4=[W2]}
No cycle: the system is deadlock-free.

  S3 -> S1 : probe(P5, P5, P1)
  S1 -> S1 : probe(P1, P1, P2)
  S1 -> S2 : probe(P1, P2, P3)
  S2 -> S3 : probe(P1, P3, P4)
  S3 -> S1 : probe(P1, P4, P1)
  *** P1 received its own probe: DEADLOCK detected at S1
Processes that detected they are deadlocked: [P1]
P5 is blocked but not deadlocked: its probe never comes back to it.
```

## Knapp's classification (self-study)
| Class | Idea | Example |
|---|---|---|
| Path-pushing | Sites send paths of the WFG to each other until one site sees a cycle | Obermarck |
| Edge-chasing | Probes follow WFG edges; a probe returning to its sender means deadlock | Chandy-Misra-Haas (Part B) |
| Diffusing computation | Queries spread through the WFG and replies come back; a complete reply set means deadlock | Chandy-Misra-Haas for the OR model |
| Global state detection | Take a consistent snapshot and look for a cycle in it | Bracha-Toueg; DeployGuard's central detector (Part A) is a simple centralized variant |

Resolution used here: abort the youngest process, because it has done the least work and its restart costs the least.

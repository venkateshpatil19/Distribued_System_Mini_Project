# Syllabus mapping: Distributed Systems (BCE27PC01)

Every topic is mapped to code, a runnable demo, a test or a written note.

**Status values:**
- **Implemented and Tested:** in the product, with automated tests or a recorded run.
- **Teaching Demonstration:** runs on the live system or in a lab and was run, but exists to illustrate the concept.
- **Conceptual:** explained in writing only.
- **Not Started:** nothing yet.

Paths are relative to the repository root. `B/` means `backend/src/main/java/com/deployguard/`, and `T/` means `backend/src/test/java/com/deployguard/`.

## Unit I: Introduction

| Topic | Evidence | Status |
|---|---|---|
| Definition and goals (resource sharing, transparency, openness, scalability) | DeployGuard: 3 controllers and N agents as separate processes behind one API; any controller answers any request (`B/controller/ApiServer.java`); agents added at run time (Lab 6) | Implemented and Tested |
| Types of distributed systems | Distributed information system (control plane plus database), pervasive-style fleet of agents; `docs/DEMO.md` | Conceptual |
| Architectures | Layered client-server (dashboard, controllers, state files), leader-based primary-backup group among controllers (gRPC mesh) | Implemented and Tested |
| Design issues (heterogeneity, failure handling, concurrency, security, scalability, transparency) | Failure detector and recovery (`B/core/FailureDetector.java`), concurrency control (`B/controller/Leases.java`), security limits stated (README, Docs page) | Implemented and Tested |
| Middleware | gRPC (RPC middleware) between every service; per-agent ordered command and report queues on top of it | Implemented and Tested |
| Distributed computation | Event-driven message passing between controllers and agents, every event stamped with Lamport and vector clocks (`B/common/NodeClock.java`) | Implemented and Tested |
| Distributed multimedia systems | QoS needs (bounded latency, jitter, bandwidth) contrasted with DeployGuard's control traffic; the live SSE event stream is the closest analogue | Conceptual |
| Self-study: model of distributed computations | Processes, channels and events with happens-before (Lab 4 `LogicalClocks.java`, product event log) | Teaching Demonstration |
| Self-study: role of virtualization | Process isolation as separate JVMs, and as separate Docker containers with their own network identity (`docker-compose.yml`); Kubernetes left out | Conceptual |

## Unit II: Communication

| Topic | Evidence | Status |
|---|---|---|
| Communication fundamentals (layers, sync and async, persistence) | Sockets (Lab 1), synchronous RPC (gRPC with deadlines), asynchronous persistent messaging (durable queues, publisher confirms) | Implemented and Tested |
| Remote Procedure Call | gRPC services `deployguard.Controller` and `deployguard.Peer` (`B/rpc/Rpc.java`, `B/controller/GrpcServices.java`); test `T/rpc/GrpcTest.java` (round trip, deadline, error status) | Implemented and Tested |
| Message-oriented communication | Asynchronous messages over gRPC: per-agent command queue delivered in heartbeat replies (`B/controller/Ctx.java`), agent report outbox resent until accepted (`B/agent/AgentMain.java`), follower event outbox forwarded to the leader (`B/controller/EventLog.java`) | Implemented and Tested |
| Stream-oriented communication | Server-Sent Events stream of all events (`/api/stream`, `B/controller/Sse.java`) consumed live by the dashboard; verified by the browser test "dashboard shows live cluster data" | Implemented and Tested |
| Peer-to-peer messaging | Controllers form a peer mesh for election and commands (`B/controller/PeerClient.java`); Lab 2 nodes talk peer to peer over TCP | Implemented and Tested |
| WebRTC | Browser peer-to-peer data channels with signaling; not built | Not Started |
| Self-study: names, identifiers, addresses | Node ids (`controller-N`, agent id plus incarnation), ids mapped to addresses in `PEERS`, flag keys as flat names | Implemented and Tested |
| Self-study: fault tolerance | Retries with backoff (`B/common/Retry.java`), idempotent writes, reconnection, pull-based catch-up; agent-partition experiment | Implemented and Tested |

## Unit III: Synchronization

| Topic | Evidence | Status |
|---|---|---|
| Physical clock synchronization | Cristian's algorithm in agents (`B/agent/AgentMain.java` `syncClock`, offsets on the Experiments page); Berkeley in `labs/lab4-clocks/Berkeley.java` | Teaching Demonstration |
| Logical clocks, Lamport's algorithm | `B/common/LamportClock.java`, on every message; `T/common/ClocksTest.java`; `labs/lab4-clocks/LogicalClocks.java` | Implemented and Tested |
| Vector clocks | `B/common/VectorClock.java`, stored per event; `/api/events/compare`; `ClocksTest`; Lab 4 | Implemented and Tested |
| Global state | Chandy-Lamport snapshot over the ordered command and report channels (`B/controller/Snapshots.java`); assumptions listed with each snapshot | Teaching Demonstration |
| Election algorithms | Bully between controllers (`B/core/BullyElection.java`, `T/core/BullyElectionTest.java`, leader-failover experiment); Bully and Ring across processes in Lab 2 | Implemented and Tested |
| Mutual exclusion | Expiring leases with fencing tokens on every flag and rollout mutation (`B/controller/Leases.java`); lease race demo; test `T/controller/StateStoreTest.java` for stale-token rejection | Implemented and Tested |
| Beacon protocol | Heartbeats every second, suspicion after 3 s, disconnection after 8 s (`B/core/FailureDetector.java`, `RolloutLogicTest.failureDetectorStates`, agent-partition experiment) | Implemented and Tested |
| Self-study: Lodha and Kshemkalyani's fair mutual exclusion | `labs/extra-fair-mutex/FairMutex.java`: safety and fairness checks, 4.8 vs 8 messages per entry against Ricart-Agrawala | Teaching Demonstration |
| Self-study: Knapp's classification of deadlock detection | Table in `labs/lab3-deadlock/README.md`; edge-chasing (Chandy-Misra-Haas) implemented in Lab 3; centralized WFG in the product (`B/core/WaitForGraph.java`, `B/controller/DeadlockDemo.java`) | Teaching Demonstration |

## Unit IV: Emerging distributed paradigms

| Topic | Evidence | Status |
|---|---|---|
| Distributed object-based systems | Java RMI demo; not built | Not Started |
| Distributed web-based systems | DeployGuard dashboard (Next.js) with REST and SSE to replicated controllers through a proxy | Implemented and Tested |
| Distributed file systems | Replicated multi-node file storage demo; not built | Not Started |
| Serverless architectures | Event-driven functions demo; not built | Not Started |
| Case study: Hadoop | Lab 5 MapReduce word count (Hadoop job written; no-Hadoop version run) | Teaching Demonstration |
| Case studies: Megaport, Cloudflare, AWS, Kubernetes | Sourced write-ups not written yet | Not Started |
| Self-study: blockchain and distributed ledgers | Tamper-evident hash-chain audit demo; not built | Not Started |
| Self-study: trade-offs in modern distributed databases | Leader-based primary-backup replication with a majority check: a minority side stops serving rather than accept conflicting writes, trading availability for consistency (CAP). Asynchronous replication can still lose the last second of writes on a crash; see `docs/DEMO.md` limitations | Conceptual |

## Laboratory assignments

| Lab | Requirement | Evidence | Status |
|---|---|---|---|
| 1 | Client-server using sockets or gRPC | `labs/lab1-client-server` (sockets, run); gRPC in the product | Implemented and Tested |
| 2 | Bully or Ring election | `labs/lab2-election` (both, 5 processes, run); Bully in the product | Implemented and Tested |
| 3 | Deadlock detection with a wait-for graph | `labs/lab3-deadlock` (run); product deadlock demo | Implemented and Tested |
| 4 | Lamport or vector clocks | `labs/lab4-clocks` (both plus Berkeley, run) | Implemented and Tested |
| 5 | Hadoop MapReduce word count | `labs/lab5-wordcount`: no-Hadoop version run; Hadoop job not executed (Hadoop unavailable in the build environment) | Teaching Demonstration |
| 6 | Distributed application with Docker and Kubernetes | DeployGuard runs as 7 Docker containers (`docker-compose.yml`, `backend/Dockerfile`, `frontend/Dockerfile`), scales with `docker compose up --scale agent=N` (measured in `docs/SCALABILITY.md`) and recovers from failures (restart policy, Bully re-election, `scripts/demo.ps1`); Kubernetes manifests not done | Implemented and Tested (Docker) |

## How to demonstrate each topic during evaluation
- **Communication, election, failure detection, rollback:** `scripts/demo.ps1` (about 3 minutes), with the dashboard open.
- **Clocks:** Experiments page, Clocks section: pick two events and press Compare; then Lab 4.
- **Mutual exclusion:** Experiments page, "Run lease race"; then `labs/extra-fair-mutex`.
- **Deadlock:** Experiments page, Deadlock with scenario "three-way", then "ordered"; then Lab 3.
- **Global state:** Experiments page, "Take snapshot".
- **Labs:** commands in `labs/README.md`.

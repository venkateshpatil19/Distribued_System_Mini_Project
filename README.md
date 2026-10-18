# Distributed Systems Mini Project – Assignment 6

**Course:** Distributed Systems (BCE27PC01)

| Name | PRN |
|---|---|
| Venkatesh Patil | 124B2B006 |
| Atharva Kshirsagar | 124B2B008 |

## DeployGuard

DeployGuard is a distributed control plane for releasing feature flags safely. It rolls a configuration change out in stages (for example 5%, 25%, 50%, 100%), watches error rate and p95 latency for the users who receive the change, and automatically rolls back or pauses when a guardrail is breached.


## How it works

| Component | What it does |
|---|---|
| Controller (3 processes) | HTTP API + live event stream (SSE), gRPC server for agents and peers, Bully leader election. Only the leader runs the rollout scheduler and the failure monitor. |
| Agent (N processes) | Registers and sends heartbeats to the leader over gRPC, gets commands (faults, traffic, snapshot markers) in the heartbeat replies, pulls new configuration revisions, acknowledges them, generates simulated traffic and reports metrics. |
| State (JSON files) | The leader keeps flags, revisions, rollouts, fencing guards and events in memory and saves them to `data/controller-N.json`. It replicates the whole state to the followers over gRPC every second, so any of them can take over. |
| Dashboard | Next.js UI in `frontend/` that proxies `/api` to any reachable controller; followers forward requests to the leader. |

Distributed mechanisms implemented in the product:

- **Bully election** between controllers over gRPC, with epochs (`core/BullyElection.java`).
- **Lamport and vector clocks** on every message and stored event (`common/LamportClock.java`, `VectorClock.java`, `NodeClock.java`).
- **Primary-backup replication** with epoch fencing: a follower refuses state from an older leader, and a leader that cannot reach a majority stops serving (`controller/Replicator.java`, `Ctx.hasQuorum`).
- **Mutual exclusion with leases and fencing tokens**: every flag or rollout change holds a lease and presents its token, which is checked in the same atomic state update (`controller/Leases.java`, `StateStore.checkFence`).
- **Deadlock detection** with a global wait-for graph and youngest-victim abort (`core/WaitForGraph.java`, `controller/DeadlockDemo.java`).
- **Chandy-Lamport snapshot** over the ordered leader-to-agent and agent-to-leader channels (`controller/Snapshots.java`).
- **Cristian's clock synchronization** from agents to controllers (`agent/AgentMain.java`).
- **Beacon failure detection**: heartbeats classify agents as healthy, delayed, suspected, disconnected, stale or recovering (`core/FailureDetector.java`).
- **Fault tolerance**: retries with backoff, agent reports resent until the leader accepts them, duplicate events dropped by id, acks that only move forward, agents that follow the leader after a failover, and atomic state-file writes.

## Prerequisites

- Java 21 (Maven 3.9+ only if you rebuild with `build.bat`)
- Node.js 20+ (for the dashboard)

No database or message broker to install: state is kept in JSON files under `data/`.

## Quick start on Windows (double-click)

| File | What it does |
|---|---|
| `start.bat` | Frees the ports it needs, finds Java 21 (even if an older Java is first on PATH), starts 3 controllers, 3 agents and the dashboard from the ready builds, and opens http://localhost:3000 |
| `build.bat` | Rebuilds the backend jar and the dashboard after you change code (needs Maven and internet) |
| `stop.bat` | Stops everything |
| `demo.bat` | Runs the narrated real-world demo (needs `start.bat` first) |
| `test.bat` | Runs backend tests, dashboard unit tests and, if the app is running, the browser tests |

Each process runs in its own minimized window titled `DG ...` and restarts by itself if it crashes. That is what makes the "Crash" buttons in the UI realistic. Logs go to `logs/`, state to `data/` (delete `data/` to start from the demo data again).

## Run with Docker

Needs only Docker Desktop. Every controller, agent and the dashboard runs in its own container on a private network, and the images are built from source.

| File | What it does |
|---|---|
| `docker-start.bat` | Builds the images (a few minutes the first time), starts the containers and opens http://localhost:3000 |
| `docker-stop.bat` | Stops and removes the containers; controller state stays in the `state` volume |
| `scale.bat 10` | Scales the agents to 10 replicas and measures join time, config propagation and throughput |

Or from any terminal:

```bash
docker compose up -d --build      # start
docker compose ps                 # 3 controllers, 3 agents, 1 dashboard
docker compose up -d --scale agent=10   # add agents live
docker compose logs -f controller-1
docker compose down               # stop (add -v to also delete the saved state)
```

| Container | Image | Ports on your machine |
|---|---|---|
| controller-1, -2, -3 | `deployguard-backend` (role `controller`) | 8081, 8082, 8083 (HTTP API) |
| agent (3 replicas by default, scalable) | `deployguard-backend` (role `agent`) | none |
| dashboard | `deployguard-dashboard` | 3000 |

Controllers find each other by container name (`controller-1:9090` and so on). Agents get the list of all three and follow the leader. A container that crashes, for example from the dashboard's Crash button, is restarted by Docker (`restart: unless-stopped`). Scale out with `--scale agent=N`; measured results are in `docs/SCALABILITY.md`.

## Manual setup (any OS)

### 1. Build

```bash
cd backend && mvn -DskipTests package && cd ..
cd frontend && npm ci && npm run build && cd ..
```

The backend build produces `backend/target/deployguard.jar`; the same jar runs both roles (`controller` or `agent`). The first leader seeds four demo flags.

### 2. Start and stop (Linux, macOS, WSL, Git Bash)

```bash
./scripts/run-local.sh
./scripts/stop-local.sh
```

This starts 3 controllers (HTTP 8081-8083, gRPC 9091-9093), 3 agents (`/healthz` and `/state` on 8101-8103) and the dashboard at **http://localhost:3000**, each under a restart loop. The dashboard talks to all three controllers and keeps working while one of them is down.

### 3. Try it from the command line

```bash
curl http://localhost:8081/api/overview                  # leader, agents, revision, traffic
curl http://localhost:8081/api/flags
curl -X POST http://localhost:8081/api/flags/new-checkout/evaluate -d '{"env":"production","context":{"userId":"u42"}}'

# Bad canary: break the new code path, then start a rollout and watch it roll back by itself
curl -X POST http://localhost:8081/api/faults -d '{"type":"unhealthy","flag":"new-checkout","errorRate":0.3,"seconds":120}'
curl -X POST http://localhost:8081/api/rollouts -d '{"flagKey":"new-checkout","env":"production","stages":[5,25,50,100],"observeSeconds":15}'
curl http://localhost:8081/api/rollouts/1              # state becomes ROLLED_BACK with the reason

# Or run the recorded experiments (bad-canary, healthy-canary, leader-failover, agent-partition, message-delay)
curl -X POST http://localhost:8081/api/experiments/runs -d '{"scenario":"leader-failover"}'
curl http://localhost:8081/api/experiments/runs

# Algorithm demonstrations
curl -X POST http://localhost:8081/api/experiments/lease-race -d '{}'
curl -X POST http://localhost:8081/api/experiments/deadlock -d '{"scenario":"three-way"}'
curl -X POST http://localhost:8081/api/experiments/snapshot
curl "http://localhost:8081/api/events?limit=20"
```

On Windows PowerShell use `curl.exe` and escape inner quotes, or use `Invoke-RestMethod`.

## API summary

| Method | Path | Purpose |
|---|---|---|
| GET | `/healthz`, `/readyz` | Liveness and readiness (a leader is elected) |
| GET | `/api/overview` | Leader, controllers, agent health, latest revision, traffic, recent events |
| GET/POST | `/api/flags` | List or create flags |
| PUT | `/api/flags/{key}/envs/{env}` | Change a flag's config in one environment (new revision) |
| POST | `/api/flags/{key}/evaluate`, `/preview` | Explain one evaluation, estimate exposure |
| GET | `/api/flags/{key}/revisions`, `/api/revisions` | Revision history |
| GET/POST | `/api/rollouts` | List or start rollouts |
| POST | `/api/rollouts/{id}/{promote,pause,resume,abort,rollback}` | Manual rollout actions |
| GET | `/api/agents`, `/api/controllers`, `/api/topology` | Cluster state |
| POST | `/api/agents/{id}/faults`, `/api/faults` | Inject delay, partition, crash, unhealthy release or clear |
| POST | `/api/controllers/{id}/faults` | Isolate, step down or crash a controller |
| GET/PUT | `/api/traffic` | Simulated traffic on/off and rate |
| GET | `/api/events`, `/api/events/compare?a=&b=`, `/api/clocks` | Event log, vector-clock comparison, Cristian offsets |
| POST | `/api/experiments/{snapshot,deadlock,lease-race,runs}` | Demonstrations and replayable experiments |
| GET | `/api/stream` | Server-Sent Events stream of all events |

## Tests

```bash
cd backend
mvn test     # clocks, evaluator, state machine, guardrails, election, wait-for graph, state store, gRPC
```

Latest run: 37 tests, 0 failures. Nothing external is needed.

Dashboard tests (with the stack running):

```bash
cd frontend
npm test                 # unit tests for formatting and clock helpers
npx playwright test      # browser tests: every page, plus every button against the live backend
```

Latest run: 32 browser tests passed. They cover creating and editing flags, environments, rollouts (start, pause, resume, promote, abort, roll back), fault injection, crashing agents and controllers, traffic control, topology, experiments, snapshots, the clock comparison and global navigation.

## Project layout

```
backend/   Java 21 Maven project (common, core, rpc, controller, agent)
frontend/  Next.js dashboard
labs/      Lab assignments 1-6 and the fair mutual-exclusion self-study (see labs/README.md)
scripts/   run/stop scripts, real-world demo (demo.ps1)
data/      state files written at run time (controller-1.json ...)
docs/      DEMO.md (demo script and viva points), SYLLABUS_MAPPING.md
```

## Real-world demo

With the app running, `demo.bat` (or `pwsh scripts/demo.ps1`) walks through an express-checkout launch:
1. A dark launch to enterprise customers.
2. A healthy staged rollout.
3. A broken release that rolls back by itself in a few seconds.
4. Leader failover.
5. Agent partition and recovery.
6. The audit trail with Lamport clocks.

See `docs/DEMO.md`.

## Notes

- gRPC uses a JSON message codec instead of generated protobuf classes; calls are still real gRPC over HTTP/2 with deadlines and status codes.
- Normal builds use Maven Central. The project also builds offline from Ubuntu's Maven repository with `mvn -s .offline-settings.xml package`; that settings file switches surefire to the 2.22.3 version Ubuntu packages.
- State lives in memory and in one JSON file per controller, kept small on purpose (latest 1500 events, 500 revisions; metrics only in memory for two hours). It suits a demo cluster, not millions of flags.
- Traffic and faults are simulated and labelled `simulated` in every event.
- There is no login. Anyone who can reach a controller can change flags and inject faults, so keep it on localhost or a private network.

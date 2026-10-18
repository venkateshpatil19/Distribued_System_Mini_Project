# Scalability

DeployGuard scales horizontally by adding agent replicas. In Docker the agents are one Compose service, so the fleet size is a single number:

```bash
docker compose up -d --scale agent=10     # or: scale.bat 10
```

Each new replica names itself after its container, registers with the leader, pulls the current configuration revision and starts serving traffic. No controller is restarted or reconfigured. Replicas removed by a scale-down stop sending heartbeats; the failure detector marks them disconnected after 8 seconds and drops them from the list after 60 seconds.

The control plane stays at three controllers on purpose. A leader needs a majority (2 of 3) to accept writes, which keeps rollout decisions consistent during failures; adding controllers would add fault tolerance, not capacity. Agents carry the load, so agents are what scale.

## Measurement

`scale.bat N` (script `scripts/scale.ps1`) scales the agent service to N replicas and records three numbers in `logs/scale-results.csv`:

- **Join time:** from `docker compose up --scale` until every replica is live and on the latest revision.
- **Config propagation:** from one configuration change until every live agent has applied it.
- **Throughput:** simulated requests per second handled by the whole fleet (average of two complete 10-second windows).

Results from one run on a single machine (Docker Compose, 3 controllers, each agent generating 40 requests/s):

| Agents | Join time | Config propagation | Requests/s |
|---:|---:|---:|---:|
| 3 | 1.0 s | 0.37 s | 122 |
| 10 | 12.2 s | 1.20 s | 398 |
| 20 | 16.5 s | 1.18 s | 796 |
| 5 (scaled down from 20) | 3.9 s | 1.16 s | 195 |

What the numbers show:

- **Throughput grows linearly** with the number of agents (about 40 requests/s each), because agents evaluate flags locally and never call the controllers on the request path.
- **Propagation time stays flat** at about one heartbeat interval (1 s) from 10 to 20 agents: every agent learns the new revision from its own heartbeat and pulls it in parallel, so the leader's work per change does not grow with the fleet.
- **Join time** is dominated by JVM and container start-up, not by the controllers.

Run `scale.bat 3`, `scale.bat 10` and `scale.bat 20` on your own machine to reproduce the table; absolute numbers depend on the CPU and memory available to Docker.

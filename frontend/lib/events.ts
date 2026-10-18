import type { DgEvent } from "./api";

// One readable sentence per event type for the activity feeds.
export function describe(e: DgEvent): string {
  const d = e.data ?? {};
  const s = (k: string) => (d[k] === undefined || d[k] === null ? "" : String(d[k]));
  switch (e.type) {
    case "LEADER_ELECTED": return `${e.nodeId} became leader (epoch ${s("epoch")}): ${s("reason")}`;
    case "LEADER_OBSERVED": return `${e.nodeId} now follows ${s("leader")}`;
    case "ELECTION_STARTED": return `${e.nodeId} started an election: ${s("reason")}`;
    case "ROLLOUT_STARTED": return `Rollout #${s("rolloutId")} started for ${s("flag")} in ${s("env")}`;
    case "ROLLOUT_PROMOTED": return `Rollout #${s("rolloutId")} promoted: ${s("reason")}`;
    case "ROLLOUT_COMPLETED": return `Rollout #${s("rolloutId")} completed`;
    case "ROLLOUT_AUTO_ROLLBACK": return `Rollout #${s("rolloutId")} rolled back automatically: ${s("detail")}`;
    case "ROLLOUT_AUTO_PAUSE": return `Rollout #${s("rolloutId")} paused automatically: ${s("detail")}`;
    case "ROLLOUT_ROLLBACK": return `Rollout #${s("rolloutId")} rolled back by ${s("actor")}`;
    case "ROLLOUT_PAUSED": return `Rollout #${s("rolloutId")} paused by ${s("actor")}`;
    case "ROLLOUT_RESUME": return `Rollout #${s("rolloutId")} resumed by ${s("actor")}`;
    case "ROLLOUT_ABORT": return `Rollout #${s("rolloutId")} aborted by ${s("actor")}`;
    case "CONFIG_CHANGED": return `${s("flag")} in ${s("env")} changed by ${s("actor")} (r${e.revision ?? "?"})`;
    case "CONFIG_APPLIED": return `${e.nodeId} applied r${s("revision")} via ${s("via")}`;
    case "CONFIG_PULLED": return `${s("agentId")} pulled r${e.revision ?? "?"} over gRPC (was r${s("fromRevision")})`;
    case "CONFIG_PUBLISH_FAILED": return `Publishing r${e.revision ?? "?"} failed; agents will pull it`;
    case "AGENT_REGISTERED": return `${s("agentId")} registered via ${s("via")}`;
    case "AGENT_SUSPECTED": return `${s("agentId")} is suspected (missed heartbeats)`;
    case "AGENT_DISCONNECTED": return `${s("agentId")} is considered disconnected`;
    case "AGENT_STALE": return `${s("agentId")} is running a stale revision`;
    case "AGENT_RECOVERED": return `${s("agentId")} recovered (${s("from").toLowerCase()} to ${s("to").toLowerCase()})`;
    case "AGENT_HEALTH_CHANGED": return `${s("agentId")}: ${s("from").toLowerCase()} to ${s("to").toLowerCase()}`;
    case "FAULT_INJECTED": return `Fault injected on ${s("target")}: ${s("type") || s("action")}`;
    case "TRAFFIC_UPDATED": return `Traffic ${d.enabled ? "on" : "off"} at ${s("rps")} req/s per agent`;
    case "SNAPSHOT_STARTED": return `Snapshot ${s("snapshotId")} started`;
    case "SNAPSHOT_COMPLETED": return `Snapshot ${s("snapshotId")} finished (${e.outcome})`;
    case "SNAPSHOT_MARKER": return `${e.nodeId} recorded its state for ${s("snapshotId")}`;
    case "DEADLOCK_DETECTED": return `Deadlock cycle ${JSON.stringify(d.cycle)} resolved by aborting ${s("victim")}`;
    case "LEASE_RACE": return `Lease race: ${s("accepted")} writes accepted, ${s("rejectedStale")} stale write rejected`;
    case "EXPERIMENT_STARTED": return `Experiment #${s("runId")} (${s("scenario")}) started`;
    case "EXPERIMENT_FINISHED": return `Experiment #${s("runId")} (${s("scenario")}) finished`;
    case "CONTROLLER_STARTED": return `${e.nodeId} started (version ${s("version")})`;
    case "FLAG_CREATED": return `Flag ${s("flag")} created by ${s("actor")}`;
    case "FLAG_UPDATED": return `Flag ${s("flag")} details updated`;
    default: return e.type.replace(/_/g, " ").toLowerCase();
  }
}

export function eventTone(e: DgEvent): "ok" | "warn" | "bad" | "info" | "neutral" {
  if (e.simulated) return "info";
  if (["error", "breach", "suspected"].includes(e.outcome)) return "bad";
  if (["partial", "injected", "stale-write-rejected"].includes(e.outcome)) return "warn";
  if (e.type.startsWith("LEADER") || e.type.startsWith("ELECTION")) return "info";
  return "ok";
}

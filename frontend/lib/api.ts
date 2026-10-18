// Small client for the controller API, reached through the Next.js proxy at /api.

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function api<T = unknown>(path: string, init?: RequestInit & { json?: unknown }): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  let body = init?.body;
  if (init?.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(init.json);
  }
  let res: Response;
  try {
    res = await fetch(`/api${path}`, { ...init, body, headers: { ...headers, ...(init?.headers as Record<string, string>) }, cache: "no-store" });
  } catch {
    throw new ApiError(0, "Network error: the control plane could not be reached.");
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text.slice(0, 200) };
    }
  }
  if (!res.ok) {
    const msg = (data as { error?: string } | null)?.error ?? `Request failed with status ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export const post = <T = unknown>(path: string, json: unknown = {}) => api<T>(path, { method: "POST", json });
export const put = <T = unknown>(path: string, json: unknown) => api<T>(path, { method: "PUT", json });
export const patch = <T = unknown>(path: string, json: unknown) => api<T>(path, { method: "PATCH", json });

// ---- Types shared by pages ----

export type Rule = { attribute: string; operator: string; values: string[]; serve: "on" | "off" };
export type EnvConfig = {
  enabled: boolean;
  rules: Rule[];
  rolloutPercent: number;
  onValue: string;
  offValue: string;
  updatedAt?: string;
};
export type ActiveRollout = { id: number; envKey: string; state: string; currentStage: number; stages: number[] };
export type Flag = {
  key: string;
  name: string;
  description: string;
  type: "boolean" | "string" | "number" | "json";
  createdAt: string;
  updatedAt: string;
  envs: Record<string, EnvConfig>;
  activeRollouts?: ActiveRollout[];
};
export type HistoryRow = {
  id: number;
  fromState: string | null;
  toState: string;
  stage: number;
  percent: number;
  revision: number | null;
  reason: string;
  actor: string;
  fenceToken: number;
  lamport: number;
  at: string;
};
export type Rollout = {
  id: number;
  flagKey: string;
  envKey: string;
  state: "RUNNING" | "PAUSED" | "COMPLETED" | "ROLLED_BACK" | "ABORTED";
  stages: number[];
  currentStage: number;
  stageStartedAt: string;
  stageRevision: number;
  observeSeconds: number;
  maxErrorRate: number;
  maxP95Ms: number;
  minRequests: number;
  onBreach: "ROLLBACK" | "PAUSE";
  autoPromote: boolean;
  lastVerdict: string | null;
  lastDetail: string | null;
  rollbackReason: string | null;
  breachDetectedAt: string | null;
  rollbackRevision: number | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
  history?: HistoryRow[];
};
export type Agent = {
  id: string;
  envKey: string;
  incarnation: number;
  registeredAt: string;
  lastHeartbeat: string;
  appliedRevision: number;
  desiredRevision: number;
  revisionLag: number;
  lastAckLagMs: number | null;
  clockOffsetMs: number | null;
  rttMs: number | null;
  activeFaults: string[];
  lastController: string | null;
  heartbeats: number;
  ageMs: number;
  health: "HEALTHY" | "DELAYED" | "SUSPECTED" | "DISCONNECTED" | "STALE" | "RECOVERING";
  live: boolean;
};
export type ControllerRow = {
  id: string;
  numericId: number;
  address: string;
  lastSeen: string;
  leaderId: string | null;
  epoch: number;
  isLeader: boolean;
  startedAt: string;
  version: string;
  ageMs: number;
  alive: boolean;
  self: boolean;
};
export type DgEvent = {
  eventId: string;
  nodeId: string;
  type: string;
  lamport: number;
  vclock: Record<string, number>;
  wallTs: string;
  recordedAt?: string;
  revision: number | null;
  outcome: string;
  simulated: boolean;
  data: Record<string, unknown>;
};
export type StreamMsg = {
  type: string;
  eventId: string;
  node: string;
  lamport: number;
  vclock: Record<string, number>;
  wallTs: number;
  body: Record<string, unknown>;
};
export type SeriesPoint = { t: string; variant: string; requests: number; errors: number; p95Ms: number; errorRate: number };
export type Overview = {
  servedBy: string;
  leader: string | null;
  epoch: number;
  controllers: ControllerRow[];
  agents: { total: number; live: number; converged: number; byHealth: Record<string, number> };
  latestRevision: number;
  activeRollouts: Array<{ id: number; flagKey: string; envKey: string; state: string; currentStage: number; stages: number[]; lastVerdict: string | null; lastDetail: string | null }>;
  traffic: { requests?: number; errors?: number };
  trafficSetting: { enabled: boolean; rps: number } | null;
  recentEvents: DgEvent[];
  lastUpdated: string | null;
  storage: { stateVersion: number; followersInSync: number; followers: number };
  duplicatesSuppressed: number;
  sseClients: number;
  build: { node: string; version: string; buildTime: string; startedAt: string };
};

// Converts a stream message into the same shape as a stored event row.
export function streamToEvent(m: StreamMsg): DgEvent {
  const body = m.body ?? {};
  return {
    eventId: m.eventId,
    nodeId: m.node,
    type: m.type,
    lamport: m.lamport,
    vclock: m.vclock,
    wallTs: new Date(m.wallTs).toISOString(),
    revision: typeof body.revision === "number" ? (body.revision as number) : null,
    outcome: String(body.outcome ?? "ok"),
    simulated: body.simulated === true,
    data: body,
  };
}

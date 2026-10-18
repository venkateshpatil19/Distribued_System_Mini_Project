"use client";

import { useMemo, useState } from "react";
import { Camera, FlaskConical, Lock, Play, Repeat, Workflow } from "lucide-react";
import { api, post, type DgEvent } from "@/lib/api";
import { compareVc, ms, relativeTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { BarList } from "@/components/charts";
import { Badge, Card, EmptyState, ErrorState, LoadingBlock, OfflineBanner, PageHeader, SectionTitle, SimulatedTag, StatusBadge, cx, useToast } from "@/components/ui";

const SECTIONS = [
  ["scenarios", "Scenarios"], ["elections", "Elections and recovery"], ["clocks", "Clocks"], ["leases", "Leases and fencing"], ["deadlock", "Deadlock"], ["snapshot", "Snapshot"],
] as const;

export function ExperimentsClient() {
  return (
    <div className="page py-10">
      <PageHeader eyebrow="Experiments" title="Watch the algorithms" accent="work">
        Everything here runs against the live controllers and agents. Scenario runs are stored so you can replay them with the same parameters and compare outcomes.
      </PageHeader>
      <OfflineBanner />
      <nav aria-label="Experiment sections" className="sticky top-16 z-30 mb-6 max-w-full overflow-x-auto border-b border-line bg-bg/85 py-2 backdrop-blur">
        <ul className="flex gap-1 whitespace-nowrap text-sm">
          {SECTIONS.map(([id, label]) => <li key={id}><a href={`#${id}`} className="rounded-lg px-3 py-1.5 text-muted hover:bg-surface-2 hover:text-ink">{label}</a></li>)}
        </ul>
      </nav>
      <div className="space-y-10">
        <Scenarios />
        <Recovery />
        <Clocks />
        <LeaseRace />
        <Deadlock />
        <Snapshot />
      </div>
    </div>
  );
}

// ---------- scenarios ----------

type Run = { id: number; scenario: string; params: Record<string, unknown>; status: string; startedAt: string; finishedAt: string | null; result: Record<string, unknown> };

const SCENARIOS: Record<string, { title: string; about: string; metrics: Array<[string, string]> }> = {
  "bad-canary": { title: "Bad canary", about: "Injects a 30% error rate into the new variant of new-checkout, starts a 10/50/100 rollout and measures detection and rollback convergence.", metrics: [["detectMs", "Time to detect breach"], ["rollbackConvergedMs", "Rollback converged after breach"]] },
  "healthy-canary": { title: "Healthy canary", about: "Same rollout with no fault; measures how long a clean three-stage rollout takes.", metrics: [["totalMs", "Total rollout time"]] },
  "leader-failover": { title: "Leader failover", about: "Isolates the current leader for 12 s and measures how fast the others elect a replacement and when the original returns.", metrics: [["newLeaderElectedMs", "New leader elected"], ["originalLeaderReturnedMs", "Original leader back"]] },
  "agent-partition": { title: "Agent partition", about: "Cuts one agent off for 10 s; measures failure-detection delay, recovery and catch-up.", metrics: [["suspectedAfterMs", "Suspected after"], ["recoveredAfterMs", "Recovered after"]] },
  "message-delay": { title: "Delayed messages", about: "Pushes a revision normally, then again while every agent delays messages by 2.5 s, and compares convergence.", metrics: [["baselineConvergenceMs", "Normal convergence"], ["delayedConvergenceMs", "Delayed convergence"]] },
};

function Scenarios() {
  const toast = useToast();
  const runs = usePoll(() => api<Run[]>("/experiments/runs?limit=40"), 2500);
  const [scenario, setScenario] = useState("bad-canary");
  const [compare, setCompare] = useState("bad-canary");
  const [busy, setBusy] = useState(false);
  const running = runs.data?.find((r) => r.status === "RUNNING");

  const start = async (path: string, body: unknown) => {
    setBusy(true);
    try {
      const r = await post<{ id: number }>(path, body);
      toast("success", `Experiment #${r.id} started.`);
      runs.refresh();
    } catch (e) {
      toast("error", e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const cmp = (runs.data ?? []).filter((r) => r.scenario === compare && r.status === "COMPLETED").slice(0, 6).reverse();

  return (
    <section id="scenarios" className="scroll-mt-32">
      <SectionTitle emoji="🧪" title="Fault scenarios">Each run is recorded with its parameters. Replay re-runs the same parameters so you can compare results. <SimulatedTag /></SectionTitle>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card accent="accent">
          <label className="block"><span className="label">Scenario</span>
            <select className="input" aria-label="Fault scenario" value={scenario} onChange={(e) => setScenario(e.target.value)}>
              {Object.entries(SCENARIOS).map(([k, v]) => <option key={k} value={k}>{v.title}</option>)}
            </select>
          </label>
          <p className="mt-3 text-sm text-muted">{SCENARIOS[scenario].about}</p>
          <button className="btn-primary mt-4" disabled={busy || !!running} onClick={() => start("/experiments/runs", { scenario })}>
            <Play className="h-4 w-4" aria-hidden /> {running ? `Run #${running.id} in progress` : "Run scenario"}
          </button>
          {running && <p className="mt-2 text-xs text-muted">One experiment runs at a time. Watch the dashboard or event feed while it runs.</p>}
        </Card>
        <Card>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <p className="font-medium">Compare runs</p>
            <select className="input !w-auto !py-1 text-xs" value={compare} onChange={(e) => setCompare(e.target.value)} aria-label="Scenario to compare">
              {Object.entries(SCENARIOS).map(([k, v]) => <option key={k} value={k}>{v.title}</option>)}
            </select>
          </div>
          {cmp.length === 0 ? <EmptyState title="No completed runs">Run this scenario at least once to compare.</EmptyState> : (
            <div className="space-y-4">
              {SCENARIOS[compare].metrics.map(([key, label]) => (
                <div key={key}>
                  <p className="mb-2 text-xs font-medium text-muted">{label}</p>
                  <BarList title={label} format={(v) => ms(v)} rows={cmp.map((r) => ({ label: `#${r.id} · ${relativeTime(r.startedAt)}`, value: typeof r.result?.[key] === "number" ? (r.result[key] as number) : null }))} />
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
      <Card className="mt-6">
        {runs.loading && !runs.data && <LoadingBlock />}
        {runs.error && !runs.data && <ErrorState message={runs.error} onRetry={runs.refresh} />}
        {runs.data && runs.data.length === 0 && <EmptyState title="No experiment runs yet" />}
        {runs.data && runs.data.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Run</th><th>Scenario</th><th>Status</th><th>Started</th><th>Result</th><th /></tr></thead>
              <tbody>
                {runs.data.map((r) => (
                  <tr key={r.id}>
                    <td className="font-mono">#{r.id}</td>
                    <td>{SCENARIOS[r.scenario]?.title ?? r.scenario}</td>
                    <td><StatusBadge value={r.status === "COMPLETED" ? "ok" : r.status === "FAILED" ? "error" : "RUNNING"} /></td>
                    <td className="whitespace-nowrap text-muted">{relativeTime(r.startedAt)}</td>
                    <td className="text-xs">
                      {Object.entries(r.result ?? {}).filter(([, v]) => v !== null && typeof v !== "object").slice(0, 5).map(([k, v]) => (
                        <span key={k} className="mr-3 inline-block"><span className="text-muted">{k}</span> <span className="font-medium">{typeof v === "number" && k.endsWith("Ms") ? ms(v) : String(v)}</span></span>
                      ))}
                    </td>
                    <td><button className="btn-ghost !px-2 !py-1 text-xs" disabled={busy || !!running} onClick={() => start(`/experiments/runs/${r.id}/replay`, {})}><Repeat className="h-3.5 w-3.5" aria-hidden /> Replay</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </section>
  );
}

// ---------- recovery metrics ----------

type Recovery = {
  elections: Array<{ leader: string; electedAt: string; durationMs: number | null; reason: string }>;
  rollbacks: Array<{ id: number; flagKey: string; envKey: string; rollbackReason: string; detectMs: number | null; convergeMs: number | null; breachDetectedAt: string | null }>;
  agentRecoveries: Array<{ agentId: string; recoveredAt: string; outageMs: number | null }>;
};

function Recovery() {
  const r = usePoll(() => api<Recovery>("/experiments/recovery"), 5000);
  return (
    <section id="elections" className="scroll-mt-32">
      <SectionTitle emoji="👑" title="Elections and recovery">Measured from the event log: election duration (first ELECTION_STARTED to LEADER_ELECTED), rollback detection and convergence, and agent outage length.</SectionTitle>
      {r.error && !r.data && <ErrorState message={r.error} onRetry={r.refresh} />}
      {r.loading && !r.data && <LoadingBlock />}
      {r.data && (
        <div className="grid gap-6 lg:grid-cols-3">
          <Card>
            <p className="mb-3 font-medium">Leader elections</p>
            {r.data.elections.length === 0 ? <p className="text-sm text-muted">No elections recorded.</p> : (
              <ul className="space-y-2 text-sm">
                {r.data.elections.map((e, i) => (
                  <li key={i} className="flex justify-between gap-2"><span><span className="font-mono">{e.leader}</span><span className="block text-xs text-muted">{e.reason}</span></span><span className="whitespace-nowrap tabular-nums">{e.durationMs === null ? "n/a" : ms(e.durationMs)}</span></li>
                ))}
              </ul>
            )}
          </Card>
          <Card>
            <p className="mb-3 font-medium">Automatic rollbacks</p>
            {r.data.rollbacks.length === 0 ? <p className="text-sm text-muted">No rollbacks yet. Try the bad canary scenario.</p> : (
              <ul className="space-y-2 text-sm">
                {r.data.rollbacks.map((x) => (
                  <li key={x.id}>
                    <p className="font-medium">#{x.id} {x.flagKey} <span className="text-muted">· {x.envKey}</span></p>
                    <p className="text-xs text-muted">detected after {ms(x.detectMs)} · converged {ms(x.convergeMs)} after breach</p>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card>
            <p className="mb-3 font-medium">Agent recoveries</p>
            {r.data.agentRecoveries.length === 0 ? <p className="text-sm text-muted">No agent outages recorded.</p> : (
              <ul className="space-y-2 text-sm">
                {r.data.agentRecoveries.map((x, i) => (
                  <li key={i} className="flex justify-between gap-2"><span className="truncate font-mono text-xs">{x.agentId}</span><span className="tabular-nums">{ms(x.outageMs)}</span></li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      )}
    </section>
  );
}

// ---------- clocks ----------

type ClocksResp = {
  self: { node: string; lamport: number; vclock: Record<string, number>; wallMs: number };
  cristian: Array<{ agentId: string; offsetMs: number | null; rttMs: number | null; errorBoundMs: number | null }>;
  events: DgEvent[];
};

function Clocks() {
  const c = usePoll(() => api<ClocksResp>("/clocks"), 0);
  const [picked, setPicked] = useState<string[]>([]);
  const [cmp, setCmp] = useState<{ relation: string; explanation: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const events = useMemo(() => c.data?.events ?? [], [c.data]);
  const pick = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p.slice(-1), id]));
  const compare = async () => {
    setErr(null);
    try {
      setCmp(await api(`/events/compare?a=${picked[0]}&b=${picked[1]}`));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  const lanes = useMemo(() => Array.from(new Set(events.map((e) => e.nodeId))).slice(0, 8), [events]);
  const sorted = useMemo(() => [...events].filter((e) => lanes.includes(e.nodeId)).sort((a, b) => a.lamport - b.lamport || a.nodeId.localeCompare(b.nodeId)), [events, lanes]);
  const local = picked.length === 2 ? (() => {
    const a = events.find((e) => e.eventId === picked[0]);
    const b = events.find((e) => e.eventId === picked[1]);
    return a && b ? compareVc(a.vclock, b.vclock) : null;
  })() : null;

  return (
    <section id="clocks" className="scroll-mt-32">
      <SectionTitle emoji="⏱️" title="Logical and physical clocks" action={<button className="btn-ghost !py-1 text-xs" onClick={c.refresh}>Refresh</button>}>
        Lamport times give a total order consistent with causality; vector clocks also reveal when two events are concurrent. Cristian&apos;s algorithm estimates each agent&apos;s clock offset (agents run with a deliberate, simulated skew).
      </SectionTitle>
      {c.error && !c.data && <ErrorState message={c.error} onRetry={c.refresh} />}
      {c.loading && !c.data && <LoadingBlock />}
      {c.data && (
        <div className="space-y-6">
          <Card>
            <p className="mb-2 font-medium">Space-time view of recent events</p>
            <p className="mb-3 text-xs text-muted">One lane per node, ordered left to right by Lamport time. Pick two dots (or rows below) to compare their causality.</p>
            <div className="overflow-x-auto">
              <svg viewBox={`0 0 ${Math.max(640, sorted.length * 22 + 160)} ${lanes.length * 34 + 20}`} className="h-auto min-w-[640px]" style={{ width: Math.max(640, sorted.length * 22 + 160) }} role="img" aria-label="Events per node ordered by Lamport time">
                {lanes.map((l, i) => (
                  <g key={l}>
                    <text x={4} y={24 + i * 34} className="fill-muted text-[10px]">{l.length > 20 ? l.slice(0, 19) + "…" : l}</text>
                    <line x1={140} x2={Math.max(640, sorted.length * 22 + 160) - 10} y1={20 + i * 34} y2={20 + i * 34} className="stroke-line" />
                  </g>
                ))}
                {sorted.map((e, i) => (
                  <circle key={e.eventId} cx={150 + i * 22} cy={20 + lanes.indexOf(e.nodeId) * 34} r={picked.includes(e.eventId) ? 7 : 5}
                    className={cx("cursor-pointer", picked.includes(e.eventId) ? "fill-accent" : "fill-info/70")} onClick={() => pick(e.eventId)}>
                    <title>{`${e.type} on ${e.nodeId}, L=${e.lamport}`}</title>
                  </circle>
                ))}
              </svg>
            </div>
          </Card>
          <div className="grid gap-6 lg:grid-cols-3">
            <Card className="lg:col-span-2">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <p className="font-medium">Events with clocks</p>
                <button className="btn-primary !py-1 text-xs" disabled={picked.length !== 2} onClick={compare}>Compare selected ({picked.length}/2)</button>
              </div>
              {err && <ErrorState message={err} />}
              {cmp && (
                <div className="mb-3 rounded-xl border border-accent/30 bg-accent-soft/50 p-3 text-sm">
                  <p className="font-semibold">{cmp.relation.replace("_", " ").toLowerCase()}</p>
                  <p className="text-muted">{cmp.explanation}</p>
                  {local && local !== cmp.relation && <p className="text-xs text-warn">Client-side check disagrees ({local}); refresh to reload events.</p>}
                </div>
              )}
              <div className="table-wrap max-h-[26rem] overflow-y-auto">
                <table className="table">
                  <thead><tr><th /><th>Event</th><th>Node</th><th>L</th><th>Vector clock</th><th>Wall clock (node)</th></tr></thead>
                  <tbody>
                    {events.map((e) => (
                      <tr key={e.eventId} className={picked.includes(e.eventId) ? "bg-accent-soft/40" : undefined}>
                        <td><input type="checkbox" aria-label={`Select ${e.type} on ${e.nodeId}`} checked={picked.includes(e.eventId)} onChange={() => pick(e.eventId)} /></td>
                        <td className="font-mono text-xs">{e.type}</td>
                        <td className="text-xs">{e.nodeId}</td>
                        <td className="tabular-nums">{e.lamport}</td>
                        <td className="max-w-[18rem] truncate font-mono text-[11px] text-muted" title={JSON.stringify(e.vclock)}>{JSON.stringify(e.vclock)}</td>
                        <td className="whitespace-nowrap text-xs text-muted">{new Date(e.wallTs).toLocaleTimeString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
            <Card>
              <p className="mb-1 font-medium">Cristian&apos;s algorithm</p>
              <p className="mb-3 text-xs text-muted">offset = (T_server + RTT/2) − T_client. Accuracy is bounded by ±RTT/2 and assumes symmetric network delay.</p>
              {c.data.cristian.length === 0 ? <p className="text-sm text-muted">No agents reporting.</p> : (
                <ul className="space-y-2 text-sm">
                  {c.data.cristian.map((x) => (
                    <li key={x.agentId} className="rounded-xl border border-line p-2">
                      <p className="truncate font-mono text-xs">{x.agentId}</p>
                      <p className="tabular-nums">offset {x.offsetMs === null ? "n/a" : `${x.offsetMs > 0 ? "+" : ""}${x.offsetMs} ms`} <span className="text-xs text-muted">· RTT {ms(x.rttMs)} · ±{x.errorBoundMs ?? "n/a"} ms</span></p>
                    </li>
                  ))}
                </ul>
              )}
              <p className="mt-3 text-xs text-muted">Controller {c.data.self.node}: L={c.data.self.lamport}</p>
            </Card>
          </div>
        </div>
      )}
    </section>
  );
}

// ---------- lease race ----------

type RaceResult = { contenders: Array<{ contender: string; node?: string; token?: number; result: string; log?: string[] }>; accepted: number; rejectedStale: number; explanation: string };

function LeaseRace() {
  const [res, setRes] = useState<RaceResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      setRes(await post<RaceResult>("/experiments/lease-race", { ttlMs: 1500, pauseMs: 3000 }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section id="leases" className="scroll-mt-32">
      <SectionTitle emoji="🔐" title="Leases and fencing tokens" action={<button className="btn-primary" disabled={busy} onClick={run}><Lock className="h-4 w-4" aria-hidden /> {busy ? "Racing..." : "Run lease race"}</button>}>
        A slow holder takes a 1.5 s lease, then stalls for 3 s (like a long GC pause). Contenders on every controller wait for expiry and take over. When the slow holder wakes up and writes, its old token is rejected.
      </SectionTitle>
      {err && <ErrorState message={err} />}
      {res ? (
        <Card accent={res.rejectedStale > 0 ? "ok" : "warn"}>
          <p className="text-sm"><strong>{res.accepted}</strong> writes accepted, <strong>{res.rejectedStale}</strong> stale write rejected. {res.explanation}</p>
          <div className="mt-4 grid gap-3 md:grid-cols-2">
            {res.contenders.map((c) => (
              <div key={c.contender} className="rounded-xl border border-line p-3">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium">{c.contender} <span className="text-xs text-muted">{c.node}</span></p>
                  <Badge tone={c.result === "ACCEPTED" ? "ok" : c.result.startsWith("REJECTED") ? "bad" : "warn"}>{c.result.toLowerCase().replace(/_/g, " ")}</Badge>
                </div>
                {c.token !== undefined && <p className="mt-1 text-xs text-muted">token {c.token}</p>}
                <ol className="mt-2 space-y-0.5 font-mono text-[11px] text-muted">{(c.log ?? []).map((l, i) => <li key={i}>{l}</li>)}</ol>
              </div>
            ))}
          </div>
        </Card>
      ) : <Card><p className="text-sm text-muted">Run the race to see tokens, takeovers and the rejected stale write.</p></Card>}
    </section>
  );
}

// ---------- deadlock ----------

type DeadlockResult = { runId: string; scenario: string; workflows: Record<string, string[]>; detections: Array<{ cycle: string[]; victim: string; detectedAfterMs: number; graph: Record<string, string[]>; rule: string }>; outcome: Record<string, string>; timeline: string[]; deadlockFound: boolean; knappCategory: string };

function WfgSvg({ graph, cycle, victim }: { graph: Record<string, string[]>; cycle: string[]; victim: string }) {
  const nodes = Array.from(new Set([...Object.keys(graph), ...Object.values(graph).flat()]));
  const R = 70;
  const pos = (i: number) => ({ x: 120 + R * Math.cos((2 * Math.PI * i) / nodes.length - Math.PI / 2), y: 100 + R * Math.sin((2 * Math.PI * i) / nodes.length - Math.PI / 2) });
  return (
    <svg viewBox="0 0 240 200" className="h-auto w-full max-w-xs" role="img" aria-label={`Wait-for graph with cycle ${cycle.join(" to ")}`}>
      <defs><marker id="wfa" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" className="fill-bad" /></marker></defs>
      {Object.entries(graph).flatMap(([from, tos]) => tos.map((to) => {
        const a = pos(nodes.indexOf(from));
        const b = pos(nodes.indexOf(to));
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const len = Math.hypot(dx, dy) || 1;
        return <line key={from + to} x1={a.x + (dx / len) * 20} y1={a.y + (dy / len) * 20} x2={b.x - (dx / len) * 22} y2={b.y - (dy / len) * 22} className="stroke-bad" strokeWidth={1.8} markerEnd="url(#wfa)" />;
      }))}
      {nodes.map((n, i) => {
        const p = pos(i);
        return (
          <g key={n}>
            <circle cx={p.x} cy={p.y} r={18} className={n === victim ? "fill-bad/15 stroke-bad" : "fill-surface stroke-line"} strokeWidth={2} />
            <text x={p.x} y={p.y + 4} textAnchor="middle" className="fill-ink text-[11px] font-semibold">{n}</text>
          </g>
        );
      })}
    </svg>
  );
}

function Deadlock() {
  const [scenario, setScenario] = useState("two-way");
  const [res, setRes] = useState<DeadlockResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      setRes(await post<DeadlockResult>("/experiments/deadlock", { scenario }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section id="deadlock" className="scroll-mt-32">
      <SectionTitle emoji="🔄" title="Deadlock detection">Workflows lock demo resources through real leases. Waiting workflows record wait-for edges on the leader; a central detector builds the global graph, finds a cycle and aborts the youngest workflow.</SectionTitle>
      <Card>
        <div className="flex flex-wrap items-end gap-3">
          <label><span className="label">Scenario</span>
            <select className="input" aria-label="Deadlock scenario" value={scenario} onChange={(e) => setScenario(e.target.value)}>
              <option value="two-way">Two workflows, opposite order (deadlock)</option>
              <option value="three-way">Three workflows in a ring (deadlock)</option>
              <option value="ordered">Same lock order (prevention, no deadlock)</option>
            </select>
          </label>
          <button className="btn-primary" disabled={busy} onClick={run}><Workflow className="h-4 w-4" aria-hidden /> {busy ? "Running..." : "Run"}</button>
        </div>
        {err && <div className="mt-3"><ErrorState message={err} /></div>}
        {res && (
          <div className="mt-5 grid gap-6 md:grid-cols-2">
            <div>
              {res.detections.length > 0 ? (
                <>
                  <WfgSvg graph={res.detections[0].graph} cycle={res.detections[0].cycle} victim={res.detections[0].victim} />
                  <p className="mt-2 text-sm">Cycle <strong>{res.detections[0].cycle.join(" → ")} → {res.detections[0].cycle[0]}</strong> found after {ms(res.detections[0].detectedAfterMs)}; aborted <strong>{res.detections[0].victim}</strong> ({res.detections[0].rule}).</p>
                </>
              ) : <p className="text-sm">No cycle formed: {res.scenario === "ordered" ? "acquiring locks in one global order makes a wait-for cycle impossible." : "the workflows finished without waiting on each other."}</p>}
              <p className="mt-2 text-xs text-muted">{res.knappCategory}</p>
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">Outcome</p>
              <div className="flex flex-wrap gap-2">{Object.entries(res.outcome).map(([w, o]) => <Badge key={w} tone={o === "COMPLETED" ? "ok" : "bad"}>{w}: {o.toLowerCase()}</Badge>)}</div>
              <p className="mb-1 mt-4 text-sm font-medium">Timeline</p>
              <ol className="space-y-0.5 font-mono text-[11px] text-muted">{res.timeline.map((t, i) => <li key={i}>{t}</li>)}</ol>
            </div>
          </div>
        )}
      </Card>
    </section>
  );
}

// ---------- snapshot ----------

type Snap = { id: string; initiator: string; status: string; startedAt: string; completedAt: string | null; state: { initiator?: Record<string, unknown>; agents?: Record<string, Record<string, unknown>>; channels?: Record<string, unknown[]>; missingAgents?: string[]; assumptions?: string[] } };

function Snapshot() {
  const list = usePoll(() => api<Snap[]>("/snapshots"), 0);
  const [snap, setSnap] = useState<Snap | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const take = async () => {
    setBusy(true);
    setErr(null);
    try {
      setSnap(await post<Snap>("/experiments/snapshot"));
      list.refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const open = async (id: string) => {
    try {
      setSnap(await api<Snap>(`/snapshots/${id}`));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  const s = snap?.state;
  return (
    <section id="snapshot" className="scroll-mt-32">
      <SectionTitle emoji="📸" title="Distributed snapshot (Chandy-Lamport)" action={<button className="btn-primary" disabled={busy} onClick={take}><Camera className="h-4 w-4" aria-hidden /> {busy ? "Recording..." : "Take snapshot"}</button>}>
        The initiating controller records its state, sends a marker down every agent&apos;s channel, and records messages that arrive on each return channel until that agent&apos;s marker comes back. Teaching demonstration.
      </SectionTitle>
      {err && <ErrorState message={err} />}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_16rem]">
        <Card>
          {!snap ? <p className="text-sm text-muted">Take a snapshot or open a previous one.</p> : (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <p className="font-mono text-sm">{snap.id}</p>
                <StatusBadge value={snap.status === "COMPLETE" ? "ok" : "partial"} />
                <span className="text-xs text-muted">by {snap.initiator}</span>
              </div>
              {s?.initiator && <p className="text-sm">Initiator state: latest revision <strong>r{String(s.initiator.latestRevision)}</strong>, {Array.isArray(s.initiator.activeRollouts) ? (s.initiator.activeRollouts as unknown[]).length : 0} active rollouts, L={String(s.initiator.lamport)}</p>}
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>Agent</th><th>Applied revision</th><th>Requests generated</th><th>Acks sent</th><th>In-flight on channel</th></tr></thead>
                  <tbody>
                    {Object.entries(s?.agents ?? {}).map(([a, st]) => (
                      <tr key={a}>
                        <td className="font-mono text-xs">{a}</td>
                        <td>r{String(st.appliedRevision)}</td>
                        <td className="tabular-nums">{String(st.requestsGenerated)}</td>
                        <td className="tabular-nums">{String(st.acksSent)}</td>
                        <td className="tabular-nums">{(s?.channels?.[a] ?? []).length} message(s)</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {s?.missingAgents && s.missingAgents.length > 0 && <ErrorState message={`No marker returned from: ${s.missingAgents.join(", ")}`} />}
              {s?.assumptions && <ul className="list-disc space-y-1 pl-5 text-xs text-muted">{s.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>}
            </div>
          )}
        </Card>
        <Card>
          <p className="mb-2 font-medium">Previous snapshots</p>
          {list.data?.length ? (
            <ul className="space-y-1 text-sm">
              {list.data.map((x) => <li key={x.id}><button className="w-full rounded-lg px-2 py-1 text-left hover:bg-surface-2" onClick={() => open(x.id)}><span className="font-mono text-xs">{x.id}</span><span className="block text-xs text-muted">{x.status.toLowerCase()} · {relativeTime(x.startedAt)}</span></button></li>)}
            </ul>
          ) : <p className="text-sm text-muted">None yet.</p>}
          <p className="mt-4 flex items-center gap-1 text-xs text-muted"><FlaskConical className="h-3.5 w-3.5" aria-hidden /> Teaching demonstration</p>
        </Card>
      </div>
    </section>
  );
}

"use client";

import { useState } from "react";
import { Bomb, Crown, Gauge, Hourglass, Scissors, Skull, Unplug, Zap } from "lucide-react";
import { api, post, put, type Agent, type ControllerRow } from "@/lib/api";
import { ms, relativeTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { Badge, Card, ConfirmButton, EmptyState, ErrorState, LastUpdated, LoadingBlock, OfflineBanner, PageHeader, SectionTitle, SimulatedTag, StatusBadge, useToast } from "@/components/ui";

type FaultForm = { type: "partition" | "delay" | "unhealthy" | "crash" | "clear"; seconds: number; delayMs: number; errorRate: number; extraLatencyMs: number; flag: string };

const FAULTS: Array<{ type: FaultForm["type"]; label: string; icon: typeof Zap; help: string }> = [
  { type: "partition", label: "Partition", icon: Scissors, help: "Stops heartbeats, config pulls and reports for the chosen time; only fault commands still get through." },
  { type: "delay", label: "Delay messages", icon: Hourglass, help: "Holds every configuration message for the chosen delay before applying it." },
  { type: "unhealthy", label: "Unhealthy release", icon: Bomb, help: "Raises errors and latency for users on the new variant of a flag." },
  { type: "crash", label: "Crash", icon: Skull, help: "Kills the agent process. The start script restarts it with a new incarnation." },
  { type: "clear", label: "Clear faults", icon: Zap, help: "Removes all active simulated faults." },
];

function faultBody(f: FaultForm) {
  switch (f.type) {
    case "partition": return { type: f.type, seconds: f.seconds };
    case "delay": return { type: f.type, seconds: f.seconds, delayMs: f.delayMs };
    case "unhealthy": return { type: f.type, seconds: f.seconds, errorRate: f.errorRate, extraLatencyMs: f.extraLatencyMs, flag: f.flag || "*" };
    default: return { type: f.type };
  }
}

export function AgentsClient() {
  const [includeRetired, setIncludeRetired] = useState(false);
  const agents = usePoll(() => api<Agent[]>(`/agents${includeRetired ? "?includeRetired=true" : ""}`), 2000, [includeRetired]);
  const ctrls = usePoll(() => api<ControllerRow[]>("/controllers"), 2000);

  return (
    <div className="page py-10">
      <PageHeader eyebrow="Agents" title="Nodes, heartbeats and" accent="faults" right={<LastUpdated at={agents.refreshedAt} />}>
        Health comes from the beacon failure detector: a missed heartbeat makes a node suspected, not proven dead. Every fault here is simulated and labelled in the event log.
      </PageHeader>
      <OfflineBanner />
      <div className="mt-4 space-y-6">
        <Card>
          <SectionTitle emoji="🖥️" title="Agents" action={
            <label className="flex items-center gap-2 text-xs text-muted"><input type="checkbox" checked={includeRetired} onChange={(e) => setIncludeRetired(e.target.checked)} /> Show retired agents</label>
          }>
            Thresholds: delayed after 2 missed beats, suspected after 3 s, disconnected after 8 s, stale when behind the latest revision for 4 s.
          </SectionTitle>
          {agents.loading && !agents.data && <LoadingBlock label="Loading agents" />}
          {agents.error && <ErrorState message={agents.error} onRetry={agents.refresh} />}
          {agents.data && agents.data.length === 0 && <EmptyState title="No agents registered">Start agents with start.bat or scripts/run-local.sh.</EmptyState>}
          {agents.data && agents.data.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Agent</th><th>Health</th><th>Last beat</th><th>Revision</th><th>Ack lag</th><th>Clock offset</th><th>Faults</th><th>Inject</th></tr></thead>
                <tbody>
                  {agents.data.map((a) => (
                    <tr key={a.id}>
                      <td><p className="font-mono text-xs">{a.id}</p><p className="text-xs text-muted">{a.envKey} · via {a.lastController ?? "n/a"}</p></td>
                      <td><StatusBadge value={a.health} /></td>
                      <td className="whitespace-nowrap tabular-nums">{ms(a.ageMs)} ago</td>
                      <td className="whitespace-nowrap font-mono text-xs">r{a.appliedRevision}{a.revisionLag > 0 && <span className="ml-1 text-warn">(-{a.revisionLag})</span>}</td>
                      <td className="tabular-nums">{ms(a.lastAckLagMs)}</td>
                      <td className="whitespace-nowrap tabular-nums" title={a.rttMs !== null ? `RTT ${a.rttMs} ms, error bound ±${a.rttMs / 2} ms` : undefined}>{a.clockOffsetMs === null ? "n/a" : `${a.clockOffsetMs > 0 ? "+" : ""}${a.clockOffsetMs} ms`}</td>
                      <td>{a.activeFaults.length ? <div className="flex flex-wrap gap-1">{a.activeFaults.map((f) => <Badge key={f} tone="warn">{f}</Badge>)}</div> : <span className="text-muted">none</span>}</td>
                      <td><InjectMenu target={a.id} onDone={agents.refresh} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <div className="grid gap-6 lg:grid-cols-2">
          <BulkFaults onDone={agents.refresh} />
          <TrafficControl />
        </div>

        <Card>
          <SectionTitle emoji="👑" title="Controllers">Bully election: the live controller with the highest id leads and runs the rollout scheduler. Isolation cuts it off from its peers without stopping it.</SectionTitle>
          {ctrls.error && !ctrls.data && <ErrorState message={ctrls.error} onRetry={ctrls.refresh} />}
          {ctrls.data && (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Controller</th><th>Role</th><th>Its view of the leader</th><th>Epoch</th><th>Last seen</th><th>Faults</th></tr></thead>
                <tbody>
                  {ctrls.data.map((c) => (
                    <tr key={c.id}>
                      <td className="font-mono text-xs">{c.id}{c.self && <span className="ml-2 text-muted">(serving this page)</span>}</td>
                      <td>{!c.alive ? <StatusBadge value="down" /> : c.isLeader ? <Badge tone="ok"><Crown className="h-3 w-3" aria-hidden /> leader</Badge> : <Badge>follower</Badge>}</td>
                      <td className="font-mono text-xs">{c.leaderId ?? "unknown"}</td>
                      <td className="tabular-nums">{c.epoch}</td>
                      <td className="whitespace-nowrap">{relativeTime(c.lastSeen)}</td>
                      <td>
                        <div className="flex flex-wrap gap-1">
                          <ConfirmButton className="!px-2 !py-1 text-xs" icon={<Unplug className="h-3.5 w-3.5" aria-hidden />} label="Isolate 15 s" title={`Isolate ${c.id}?`} confirmLabel="Isolate" disabled={!c.alive}
                            body={<p>{c.id} stops exchanging election traffic for 15 seconds. {c.isLeader ? "The others will elect a new leader; when the partition heals, Bully hands leadership back to the highest id." : "If it is not the leader, nothing visible should change."}</p>}
                            onConfirm={() => post(`/controllers/${c.id}/faults`, { action: "isolate", seconds: 15 })} />
                          <ConfirmButton className="!px-2 !py-1 text-xs" icon={<Skull className="h-3.5 w-3.5" aria-hidden />} label="Crash" title={`Crash ${c.id}?`} confirmLabel="Crash process" disabled={!c.alive}
                            body={<p>The process exits immediately. The start script restarts it; in the meantime the others re-elect if it was the leader.</p>}
                            onConfirm={() => post(`/controllers/${c.id}/faults`, { action: "crash" })} />
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

function InjectMenu({ target, onDone }: { target: string; onDone: () => void }) {
  const toast = useToast();
  const [type, setType] = useState<FaultForm["type"]>("partition");
  const run = async () => {
    const f: FaultForm = { type, seconds: type === "unhealthy" ? 60 : 10, delayMs: 2500, errorRate: 0.3, extraLatencyMs: 200, flag: "*" };
    await post(`/agents/${encodeURIComponent(target)}/faults`, faultBody(f));
    toast("success", `${type} sent to ${target}.`);
    onDone();
  };
  const meta = FAULTS.find((f) => f.type === type)!;
  return (
    <div className="flex items-center gap-1">
      <select className="input !w-auto !py-1 text-xs" value={type} onChange={(e) => setType(e.target.value as FaultForm["type"])} aria-label={`Fault for ${target}`}>
        {FAULTS.map((f) => <option key={f.type} value={f.type}>{f.label}</option>)}
      </select>
      <ConfirmButton className="!px-2 !py-1 text-xs" label="Go" title={`${meta.label} on ${target}?`} confirmLabel="Inject" danger={type !== "clear"}
        body={<><p>{meta.help}</p><p>Defaults: {type === "unhealthy" ? "60 s, 30% errors, +200 ms on every flag's new variant" : type === "delay" ? "2.5 s delay for 10 s" : type === "partition" ? "10 seconds" : "immediate"}.</p></>}
        onConfirm={run} />
    </div>
  );
}

function BulkFaults({ onDone }: { onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState<FaultForm>({ type: "unhealthy", seconds: 60, delayMs: 2500, errorRate: 0.3, extraLatencyMs: 200, flag: "new-checkout" });
  const meta = FAULTS.find((x) => x.type === f.type)!;
  return (
    <Card accent="warn">
      <div id="faults" className="scroll-mt-24" />
      <SectionTitle emoji="💥" title="Inject a fault on every agent"><SimulatedTag /></SectionTitle>
      <div role="radiogroup" aria-label="Fault type" className="flex flex-wrap gap-2">
        {FAULTS.map((x) => (
          <button key={x.type} type="button" role="radio" aria-checked={f.type === x.type} onClick={() => setF({ ...f, type: x.type })}
            className={`btn !px-3 !py-1.5 text-xs ${f.type === x.type ? "bg-accent text-accent-ink" : "border border-line bg-surface hover:border-accent/50"}`}>
            <x.icon className="h-3.5 w-3.5" aria-hidden /> {x.label}
          </button>
        ))}
      </div>
      <p className="mt-3 text-sm text-muted">{meta.help}</p>
      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
        {f.type !== "crash" && f.type !== "clear" && <label><span className="label">Duration (s)</span><input type="number" min={1} max={600} className="input" value={f.seconds} onChange={(e) => setF({ ...f, seconds: Number(e.target.value) })} /></label>}
        {f.type === "delay" && <label><span className="label">Delay (ms)</span><input type="number" min={1} max={30000} className="input" value={f.delayMs} onChange={(e) => setF({ ...f, delayMs: Number(e.target.value) })} /></label>}
        {f.type === "unhealthy" && (
          <>
            <label><span className="label">Flag (* for all)</span><input className="input font-mono" value={f.flag} onChange={(e) => setF({ ...f, flag: e.target.value })} /></label>
            <label><span className="label">Error rate (0-1)</span><input type="number" step="0.05" min={0} max={1} className="input" value={f.errorRate} onChange={(e) => setF({ ...f, errorRate: Number(e.target.value) })} /></label>
            <label><span className="label">Extra latency (ms)</span><input type="number" min={0} max={5000} className="input" value={f.extraLatencyMs} onChange={(e) => setF({ ...f, extraLatencyMs: Number(e.target.value) })} /></label>
          </>
        )}
      </div>
      <div className="mt-4">
        <ConfirmButton label={`Apply to all agents`} title={`${meta.label} on every agent?`} confirmLabel="Inject" danger={f.type !== "clear"}
          body={<p>{meta.help} This affects every agent subscribed to the control exchange.</p>}
          onConfirm={async () => { await post("/faults", faultBody(f)); toast("success", `${meta.label} sent to all agents.`); onDone(); }} />
      </div>
    </Card>
  );
}

function TrafficControl() {
  const toast = useToast();
  const t = usePoll(() => api<{ enabled: boolean; rps: number }>("/traffic"), 0);
  const [rps, setRps] = useState<number | null>(null);
  const cur = t.data;
  const save = async (enabled: boolean) => {
    try {
      const out = await put<{ enabled: boolean; rps: number }>("/traffic", { enabled, rps: rps ?? cur?.rps ?? 40 });
      toast("success", `Traffic ${out.enabled ? "on" : "off"} at ${out.rps} req/s per agent.`);
      t.refresh();
    } catch (e) {
      toast("error", e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Card>
      <SectionTitle emoji="🚗" title="Simulated traffic">Each agent generates synthetic requests that evaluate every flag. Guardrails are computed from these. <SimulatedTag /></SectionTitle>
      {t.error && <ErrorState message={t.error} onRetry={t.refresh} />}
      {cur && (
        <div className="flex flex-wrap items-end gap-3">
          <p className="flex items-center gap-2 text-sm"><Gauge className="h-4 w-4 text-accent" aria-hidden /> Currently <strong>{cur.enabled ? "on" : "off"}</strong> at {cur.rps} req/s per agent</p>
          <label className="ml-auto"><span className="label">Requests/s per agent</span>
            <input type="number" min={1} max={500} className="input !w-28" value={rps ?? cur.rps} onChange={(e) => setRps(Number(e.target.value))} />
          </label>
          <button className="btn-primary" onClick={() => save(true)}>Apply</button>
          {cur.enabled && <button className="btn-ghost" onClick={() => save(false)}>Stop traffic</button>}
        </div>
      )}
    </Card>
  );
}

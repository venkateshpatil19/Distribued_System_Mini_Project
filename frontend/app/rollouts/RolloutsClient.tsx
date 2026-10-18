"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { FastForward, Pause, Play, Rocket, RotateCcw, Square } from "lucide-react";
import { api, post, type Flag, type Rollout, type SeriesPoint } from "@/lib/api";
import { ms, pct, relativeTime, stageLabel } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { C, LineChart } from "@/components/charts";
import { Card, ConfirmButton, EmptyState, ErrorState, LastUpdated, LoadingBlock, OfflineBanner, PageHeader, SectionTitle, SimulatedTag, StatusBadge, cx, useToast } from "@/components/ui";

export function RolloutsClient() {
  const params = useSearchParams();
  const router = useRouter();
  const list = usePoll(() => api<Rollout[]>("/rollouts?limit=50"), 3000);
  const selectedId = Number(params.get("id")) || list.data?.find((r) => r.state === "RUNNING" || r.state === "PAUSED")?.id || list.data?.[0]?.id || null;
  const select = (id: number) => router.replace(`/rollouts?id=${id}`, { scroll: false });

  return (
    <div className="page py-10">
      <PageHeader eyebrow="Rollouts" title="Staged releases with" accent="guardrails" right={<LastUpdated at={list.refreshedAt} />}>
        The leader evaluates each running stage every second. A breach rolls back (or pauses) automatically; healthy and converged stages promote.
      </PageHeader>
      <OfflineBanner />
      <div className="mt-4 grid gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
        <div className="space-y-6">
          <NewRollout onCreated={(id) => { list.refresh(); select(id); }} />
          <Card>
            <SectionTitle emoji="📜" title="All rollouts" />
            {list.loading && !list.data && <LoadingBlock />}
            {list.error && !list.data && <ErrorState message={list.error} onRetry={list.refresh} />}
            {list.data && list.data.length === 0 && <EmptyState title="No rollouts yet">Use the form above to start one.</EmptyState>}
            <ul className="space-y-2">
              {list.data?.map((r) => (
                <li key={r.id}>
                  <button type="button" onClick={() => select(r.id)} aria-current={selectedId === r.id}
                    className={cx("w-full rounded-xl border p-3 text-left text-sm transition", selectedId === r.id ? "border-accent bg-accent-soft/60" : "border-line hover:border-accent/50")}>
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium">#{r.id} {r.flagKey}</span>
                      <StatusBadge value={r.state} />
                    </div>
                    <p className="mt-1 text-xs text-muted">{r.envKey} · {stageLabel(r.stages, r.currentStage)} · {relativeTime(r.createdAt)}</p>
                  </button>
                </li>
              ))}
            </ul>
          </Card>
        </div>
        <div className="min-w-0">
          {selectedId ? <RolloutDetail id={selectedId} onChanged={list.refresh} /> : <Card><EmptyState title="Select a rollout" /></Card>}
        </div>
      </div>
    </div>
  );
}

function NewRollout({ onCreated }: { onCreated: (id: number) => void }) {
  const toast = useToast();
  const flags = usePoll(() => api<Flag[]>("/flags"), 0);
  const envs = usePoll(() => api<Array<{ key: string; name: string }>>("/environments"), 0);
  const [f, setF] = useState({ flagKey: "", env: "production", stages: "5, 25, 50, 100", observeSeconds: 20, maxErrorRatePct: 5, maxP95Ms: 250, minRequests: 50, onBreach: "ROLLBACK", autoPromote: true });
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const flagKey = f.flagKey || flags.data?.[0]?.key || "";

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    const stages = f.stages.split(",").map((s) => Number(s.trim())).filter((n) => !Number.isNaN(n));
    if (!stages.length || stages.some((s, i) => s < 1 || s > 100 || (i > 0 && s <= stages[i - 1]))) {
      setErr("Stages must be increasing percentages between 1 and 100, for example 5, 25, 50, 100.");
      return;
    }
    setBusy(true);
    try {
      const r = await post<Rollout>("/rollouts", {
        requestId: crypto.randomUUID(), flagKey, env: f.env, stages, observeSeconds: f.observeSeconds,
        maxErrorRate: f.maxErrorRatePct / 100, maxP95Ms: f.maxP95Ms, minRequests: f.minRequests, onBreach: f.onBreach, autoPromote: f.autoPromote,
      });
      toast("success", `Rollout #${r.id} started at ${stages[0]}% (revision r${r.stageRevision}).`);
      onCreated(r.id);
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card accent="accent">
      <div id="new" className="scroll-mt-24" />
      <SectionTitle emoji="🚀" title="Start a rollout" />
      <form onSubmit={submit} className="grid gap-3">
        <div className="grid grid-cols-2 gap-3">
          <label><span className="label">Flag</span>
            <select className="input" aria-label="Flag" value={flagKey} onChange={(e) => setF({ ...f, flagKey: e.target.value })} required>
              {(flags.data ?? []).map((x) => <option key={x.key} value={x.key}>{x.key}</option>)}
            </select>
          </label>
          <label><span className="label">Environment</span>
            <select className="input" aria-label="Environment" value={f.env} onChange={(e) => setF({ ...f, env: e.target.value })}>
              {(envs.data ?? [{ key: "production", name: "Production" }]).map((x) => <option key={x.key} value={x.key}>{x.name}</option>)}
            </select>
          </label>
        </div>
        <label><span className="label">Stages (%)</span><input className="input font-mono" value={f.stages} onChange={(e) => setF({ ...f, stages: e.target.value })} /></label>
        <div className="grid grid-cols-2 gap-3">
          <label><span className="label">Observe per stage (s)</span><input type="number" min={5} max={3600} className="input" value={f.observeSeconds} onChange={(e) => setF({ ...f, observeSeconds: Number(e.target.value) })} /></label>
          <label><span className="label">Min requests</span><input type="number" min={1} className="input" value={f.minRequests} onChange={(e) => setF({ ...f, minRequests: Number(e.target.value) })} /></label>
          <label><span className="label">Max error rate (%)</span><input type="number" step="0.1" min={0.1} max={100} className="input" value={f.maxErrorRatePct} onChange={(e) => setF({ ...f, maxErrorRatePct: Number(e.target.value) })} /></label>
          <label><span className="label">Max p95 (ms)</span><input type="number" min={1} className="input" value={f.maxP95Ms} onChange={(e) => setF({ ...f, maxP95Ms: Number(e.target.value) })} /></label>
        </div>
        <div className="flex flex-wrap items-center gap-4">
          <label className="flex items-center gap-2 text-sm"><span className="text-muted">On breach</span>
            <select className="input !w-auto" aria-label="On breach" value={f.onBreach} onChange={(e) => setF({ ...f, onBreach: e.target.value })}>
              <option value="ROLLBACK">roll back</option><option value="PAUSE">pause</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.autoPromote} onChange={(e) => setF({ ...f, autoPromote: e.target.checked })} /> Auto-promote</label>
        </div>
        {err && <ErrorState message={err} />}
        <button className="btn-primary" disabled={busy || !flagKey} type="submit"><Rocket className="h-4 w-4" aria-hidden /> {busy ? "Starting..." : "Start rollout"}</button>
      </form>
    </Card>
  );
}

function RolloutDetail({ id, onChanged }: { id: number; onChanged: () => void }) {
  const toast = useToast();
  const r = usePoll(() => api<Rollout>(`/rollouts/${id}`), 2000, [id]);
  const series = usePoll(() => api<SeriesPoint[]>(`/rollouts/${id}/metrics?minutes=15`), 5000, [id]);
  const d = r.data;
  if (r.loading && !d) return <Card><LoadingBlock /></Card>;
  if (!d) return <Card><ErrorState message={r.error ?? "Rollout not found"} onRetry={r.refresh} /></Card>;

  const act = (action: string) => async () => {
    const out = await post<Rollout>(`/rollouts/${id}/${action}`, { reason: `${action} from dashboard` });
    toast("success", `Rollout #${id} is now ${out.state.toLowerCase().replace("_", " ")}.`);
    r.refresh();
    onChanged();
  };
  const activeState = d.state === "RUNNING" || d.state === "PAUSED";
  const atLast = d.currentStage >= d.stages.length - 1;
  const buckets = Array.from(new Set((series.data ?? []).map((p) => p.t))).sort();
  const val = (variant: string, f: (p: SeriesPoint) => number | null) => buckets.map((t) => {
    const p = series.data?.find((x) => x.t === t && x.variant === variant);
    return p ? f(p) : null;
  });
  const labels = buckets.map((t) => new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }));

  return (
    <div className="space-y-6">
      <Card accent={d.state === "ROLLED_BACK" ? "bad" : d.state === "PAUSED" ? "warn" : d.state === "COMPLETED" ? "ok" : "accent"}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold">Rollout #{d.id}: {d.flagKey} <span className="font-normal text-muted">in {d.envKey}</span></h2>
            <p className="mt-1 text-xs text-muted">Started {relativeTime(d.createdAt)} by {d.createdBy} · stage revision r{d.stageRevision}</p>
          </div>
          <StatusBadge value={d.state} />
        </div>
        <ol className="mt-5 grid gap-2" style={{ gridTemplateColumns: `repeat(${d.stages.length}, minmax(0, 1fr))` }} aria-label="Stages">
          {d.stages.map((s, i) => {
            const done = i < d.currentStage || d.state === "COMPLETED";
            const current = i === d.currentStage && activeState;
            return (
              <li key={i} className={cx("rounded-xl border p-2 text-center text-sm", current ? "border-accent bg-accent-soft font-semibold text-accent" : done ? "border-ok/40 bg-ok/5" : "border-line text-muted")}>
                {s}%<span className="block text-[10px] font-normal">{current ? "observing" : done ? "passed" : "pending"}</span>
              </li>
            );
          })}
        </ol>
        {d.rollbackReason && <div className="mt-4"><ErrorState message={`Rolled back: ${d.rollbackReason}`} /></div>}
        <dl className="mt-5 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <div className="flex justify-between gap-2"><dt className="text-muted">Guardrail verdict</dt><dd>{d.lastVerdict ? <StatusBadge value={d.lastVerdict} /> : "waiting"}</dd></div>
          <div className="flex justify-between gap-2"><dt className="text-muted">Policy</dt><dd className="text-right">errors ≤ {pct(d.maxErrorRate)}, p95 ≤ {ms(d.maxP95Ms)}</dd></div>
          <div className="flex justify-between gap-2"><dt className="text-muted">Observation window</dt><dd>{d.observeSeconds} s, min {d.minRequests} requests</dd></div>
          <div className="flex justify-between gap-2"><dt className="text-muted">On breach</dt><dd>{d.onBreach.toLowerCase()} · auto-promote {d.autoPromote ? "on" : "off"}</dd></div>
        </dl>
        {d.lastDetail && <p className="mt-3 rounded-xl bg-surface-2 px-3 py-2 text-sm text-muted">{d.lastDetail}</p>}
        {activeState && (
          <div className="mt-5 flex flex-wrap gap-2">
            <ConfirmButton danger={false} icon={<FastForward className="h-4 w-4" aria-hidden />} label={atLast ? "Complete" : "Promote now"} title={atLast ? "Complete this rollout?" : "Promote to the next stage?"}
              body={<p>This is a manual override. It skips the remaining observation time{atLast ? " and marks the rollout complete" : ` and exposes ${d.stages[d.currentStage + 1]}% of users`}.</p>}
              confirmLabel={atLast ? "Complete" : "Promote"} onConfirm={act("promote")} />
            {d.state === "RUNNING" && <button className="btn-ghost" onClick={() => act("pause")().catch((e) => toast("error", String(e.message ?? e)))}><Pause className="h-4 w-4" aria-hidden /> Pause</button>}
            {d.state === "PAUSED" && <button className="btn-ghost" onClick={() => act("resume")().catch((e) => toast("error", String(e.message ?? e)))}><Play className="h-4 w-4" aria-hidden /> Resume</button>}
            <ConfirmButton icon={<Square className="h-4 w-4" aria-hidden />} label="Abort" title="Abort this rollout?" confirmLabel="Abort rollout"
              body={<p>Orchestration stops and the flag stays at its current exposure ({d.stages[d.currentStage]}%). Use roll back to restore the previous configuration instead.</p>} onConfirm={act("abort")} />
            <ConfirmButton icon={<RotateCcw className="h-4 w-4" aria-hidden />} label="Roll back" title="Roll back this rollout?" confirmLabel="Roll back"
              body={<p>The previous configuration of <strong>{d.flagKey}</strong> in {d.envKey} becomes a new revision and is pushed to every agent.</p>} onConfirm={act("rollback")} />
          </div>
        )}
      </Card>

      <Card>
        <SectionTitle emoji="📊" title="New variant versus control">Error rate and p95 latency per 10-second window for users on and off the flag. <SimulatedTag /></SectionTitle>
        {series.error && !series.data && <ErrorState message={series.error} onRetry={series.refresh} />}
        {series.data && (
          <div className="grid gap-6">
            <LineChart title="Error rate on versus off" labels={labels} format={(v) => pct(v)}
              series={[{ name: "on (new)", color: C.bad, values: val("on", (p) => p.errorRate) }, { name: "off (control)", color: C.muted, values: val("off", (p) => p.errorRate) }]} />
            <LineChart title="p95 latency on versus off" labels={labels} height={140} format={(v) => `${Math.round(v)} ms`}
              series={[{ name: "on (new)", color: C.info, values: val("on", (p) => (p.p95Ms < 0 ? null : p.p95Ms)) }, { name: "off (control)", color: C.muted, values: val("off", (p) => (p.p95Ms < 0 ? null : p.p95Ms)) }]} />
          </div>
        )}
      </Card>

      <Card>
        <SectionTitle emoji="🧾" title="Transition history">Every state change with the fencing token of the lease that authorised it and the controller&apos;s Lamport time.</SectionTitle>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>When</th><th>Transition</th><th>Stage</th><th>Revision</th><th>Reason</th><th>Actor</th><th>Token</th><th>L</th></tr></thead>
            <tbody>
              {(d.history ?? []).map((h) => (
                <tr key={h.id}>
                  <td className="whitespace-nowrap text-muted"><time dateTime={h.at} title={h.at}>{relativeTime(h.at)}</time></td>
                  <td className="whitespace-nowrap">{h.fromState ? `${h.fromState.toLowerCase()} → ` : ""}{h.toState.toLowerCase()}</td>
                  <td>{h.percent >= 0 ? `${h.percent}%` : "baseline"}</td>
                  <td className="font-mono">{h.revision ? `r${h.revision}` : "n/a"}</td>
                  <td className="max-w-[20rem]">{h.reason}</td>
                  <td className="text-muted">{h.actor}</td>
                  <td className="font-mono text-muted">{h.fenceToken}</td>
                  <td className="font-mono text-muted">{h.lamport}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

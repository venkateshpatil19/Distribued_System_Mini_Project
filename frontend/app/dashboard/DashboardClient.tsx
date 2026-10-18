"use client";

import Link from "next/link";
import { Activity, ArrowRight, Crown, Database, Radio, Server } from "lucide-react";
import { api, type Overview, type SeriesPoint } from "@/lib/api";
import { compact, pct, stageLabel } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { C, LineChart } from "@/components/charts";
import { EventFeed } from "@/components/EventFeed";
import { Badge, Card, EmptyState, ErrorState, LastUpdated, MetricCard, OfflineBanner, PageHeader, SectionTitle, SimulatedTag, Skeleton, StatusBadge } from "@/components/ui";

export function DashboardClient() {
  const ov = usePoll(() => api<Overview>("/overview"), 3000);
  const series = usePoll(() => api<SeriesPoint[]>("/metrics/series?flag=__all__&env=*&minutes=10"), 10000);
  const d = ov.data;
  const req = Number(d?.traffic?.requests ?? 0);
  const err = Number(d?.traffic?.errors ?? 0);

  const labels = (series.data ?? []).map((p) => new Date(p.t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }));

  return (
    <div className="page py-10">
      <PageHeader eyebrow="Dashboard" title="Live system" accent="health"
        right={<div className="flex flex-col items-end gap-1">
          <LastUpdated at={ov.refreshedAt} />
          {d?.lastUpdated && <LastUpdated at={d.lastUpdated} label="Last cluster change" />}
        </div>}>
        What every controller and agent is doing right now. Served by <span className="font-mono text-ink">{d?.servedBy ?? "..."}</span>; any controller can answer.
      </PageHeader>
      <div className="space-y-6">
        <OfflineBanner />
        {ov.error && <ErrorState message={`Could not refresh: ${ov.error}`} onRetry={ov.refresh} />}

        {!d ? (
          <div className="grid gap-4 sm:grid-cols-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-[76px]" />)}</div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-3">
            <MetricCard icon={<Crown className="h-5 w-5" />} label="Leader" value={d.leader ?? "electing"} hint={`epoch ${d.epoch} · ${d.controllers.filter((c) => c.alive).length} controllers alive`} />
            <MetricCard icon={<Server className="h-5 w-5" />} label="Agents converged" value={`${d.agents.converged} / ${d.agents.live}`} hint={`live agents on r${d.latestRevision}`} tone={d.agents.converged === d.agents.live ? "ok" : "warn"} />
            <MetricCard icon={<Activity className="h-5 w-5" />} label="Requests, last 60 s" value={compact(req)} hint={<>error rate {pct(req ? err / req : 0, 2)} <SimulatedTag /></>} tone={req && err / req > 0.05 ? "bad" : "info"} />
          </div>
        )}

        {d && (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <Badge><Database className="h-3 w-3" aria-hidden /> state version {d.storage.stateVersion}</Badge>
            <Badge tone={d.storage.followersInSync === d.storage.followers ? "ok" : "warn"}><Radio className="h-3 w-3" aria-hidden /> replicated to {d.storage.followersInSync} / {d.storage.followers} followers</Badge>
            <Badge>duplicates suppressed: {d.duplicatesSuppressed}</Badge>
            <Badge>dashboard streams: {d.sseClients}</Badge>
          </div>
        )}

        <div className="grid gap-6 lg:grid-cols-5">
          <Card className="lg:col-span-3">
            <SectionTitle emoji="📈" title="Traffic across all agents">Simulated requests per 10-second window, with errors and bucketed p95 latency.</SectionTitle>
            {series.error && !series.data && <ErrorState message={series.error} onRetry={series.refresh} />}
            {series.data && (() => {
              const all = series.data.filter((p) => p.variant === "all");
              const lab = all.map((p) => new Date(p.t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }));
              return (
                <div className="grid gap-6">
                  <LineChart title="Requests and errors per window" labels={lab} format={(v) => compact(Math.round(v))}
                    series={[{ name: "requests", color: C.accent, values: all.map((p) => p.requests) }, { name: "errors", color: C.bad, values: all.map((p) => p.errors) }]} />
                  <LineChart title="p95 latency (ms)" labels={lab} height={120} format={(v) => `${Math.round(v)} ms`}
                    series={[{ name: "p95 latency", color: C.info, values: all.map((p) => (p.p95Ms < 0 ? null : p.p95Ms)) }]} />
                </div>
              );
            })()}
            {!series.data && !series.error && <Skeleton className="h-48" />}
            {labels.length === 0 && series.data && <p className="sr-only">No data</p>}
          </Card>

          <Card className="lg:col-span-2">
            <SectionTitle emoji="🚦" title="Active rollouts" action={<Link href="/rollouts" className="text-sm text-accent hover:underline">Manage</Link>} />
            {!d ? <Skeleton className="h-24" /> : d.activeRollouts.length === 0 ? (
              <EmptyState title="Nothing rolling out" action={<Link href="/rollouts#new" className="btn-primary">Start a rollout</Link>}>Start a staged rollout to see guardrails at work.</EmptyState>
            ) : (
              <ul className="space-y-3">
                {d.activeRollouts.map((r) => (
                  <li key={r.id} className="rounded-xl border border-line p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <Link href={`/rollouts?id=${r.id}`} className="font-medium hover:text-accent">#{r.id} {r.flagKey} <span className="text-muted">· {r.envKey}</span></Link>
                      <StatusBadge value={r.state} />
                    </div>
                    <div className="mt-2 flex gap-1" aria-label={stageLabel(r.stages, r.currentStage)}>
                      {r.stages.map((s, i) => (
                        <span key={i} className={`h-1.5 flex-1 rounded-full ${i <= r.currentStage ? "bg-accent" : "bg-surface-2"}`} title={`${s}%`} />
                      ))}
                    </div>
                    <p className="mt-2 text-xs text-muted">{stageLabel(r.stages, r.currentStage)} · {r.lastVerdict ? r.lastVerdict.replace(/_/g, " ").toLowerCase() : "waiting for data"}</p>
                    {r.lastDetail && <p className="mt-1 text-xs text-muted">{r.lastDetail}</p>}
                  </li>
                ))}
              </ul>
            )}
            {d && (
              <div className="mt-6">
                <p className="eyebrow mb-2">Agent health</p>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(d.agents.byHealth).map(([h, n]) => <span key={h} className="flex items-center gap-1"><StatusBadge value={h} /><span className="text-xs tabular-nums">{n}</span></span>)}
                  {Object.keys(d.agents.byHealth).length === 0 && <span className="text-sm text-muted">No agents registered</span>}
                </div>
                <Link href="/agents" className="mt-3 inline-flex items-center gap-1 text-sm text-accent hover:underline">Agents and faults <ArrowRight className="h-3.5 w-3.5" aria-hidden /></Link>
              </div>
            )}
          </Card>
        </div>

        <Card>
          <SectionTitle emoji="🛰️" title="Recent activity">Every consequential event, with the node that produced it and its Lamport time. New events arrive over Server-Sent Events.</SectionTitle>
          <EventFeed limit={40} />
        </Card>
      </div>
    </div>
  );
}

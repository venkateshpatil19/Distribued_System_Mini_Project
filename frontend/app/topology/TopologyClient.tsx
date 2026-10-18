"use client";

import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import { Card, ErrorState, LastUpdated, LoadingBlock, OfflineBanner, PageHeader, StatusBadge, cx } from "@/components/ui";

type Node = { id: string; kind: "controller" | "agent" | "storage"; label: string; status: string; leader?: boolean; epoch?: number; env?: string; appliedRevision?: number; faults?: string[] };
type Edge = { from: string; to: string; kind: string };
type Topo = { nodes: Node[]; edges: Edge[]; servedBy: string };

const EDGE_STYLE: Record<string, { cls: string; dash?: string; label: string }> = {
  grpc: { cls: "stroke-info", dash: "5 4", label: "gRPC heartbeat / register" },
  "grpc-peer": { cls: "stroke-accent", dash: "2 4", label: "gRPC election + state replication" },
  file: { cls: "stroke-warn", label: "JSON state file" },
};

function statusClass(s: string) {
  if (["up", "HEALTHY"].includes(s)) return "stroke-ok";
  if (["DELAYED", "STALE", "RECOVERING"].includes(s)) return "stroke-warn";
  return "stroke-bad";
}

export function TopologyClient() {
  const t = usePoll(() => api<Topo>("/topology"), 2000);
  const [sel, setSel] = useState<string | null>(null);
  const [hover, setHover] = useState<string | null>(null);

  const layout = useMemo(() => {
    const pos: Record<string, { x: number; y: number }> = {};
    if (!t.data) return { pos, H: 400 };
    const ctrls = t.data.nodes.filter((n) => n.kind === "controller");
    const agents = t.data.nodes.filter((n) => n.kind === "agent");
    const H = Math.max(420, 80 + Math.max(ctrls.length * 110, Math.ceil(agents.length / 2) * 90));
    ctrls.forEach((n, i) => {
      const y = (H / (ctrls.length + 1)) * (i + 1);
      pos[n.id] = { x: 330, y };
      pos[n.id + "-state"] = { x: 95, y };
    });
    const rows = Math.ceil(agents.length / 2) || 1;
    agents.forEach((n, i) => (pos[n.id] = { x: 650 + (i % 2) * 150, y: (H / (rows + 1)) * (Math.floor(i / 2) + 1) }));
    return { pos, H };
  }, [t.data]);

  const focus = hover ?? sel;
  const linked = (e: Edge) => focus !== null && (e.from === focus || e.to === focus);
  const selected = t.data?.nodes.find((n) => n.id === sel) ?? null;

  return (
    <div className="page py-10">
      <PageHeader eyebrow="Topology" title="Who talks to" accent="whom" right={<LastUpdated at={t.refreshedAt} />}>
        Live map of the cluster as seen by {t.data?.servedBy ?? "a controller"}. Hover or select a node to highlight its connections; the crowned controller is the current leader.
      </PageHeader>
      <OfflineBanner />
      {t.error && !t.data && <ErrorState message={t.error} onRetry={t.refresh} />}
      {t.loading && !t.data && <LoadingBlock />}
      {t.data && (
        <div className="mt-4 grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
          <Card className="overflow-x-auto !p-3">
            <svg viewBox={`0 0 880 ${layout.H}`} className="h-auto w-full min-w-[640px]" role="img" aria-label="Cluster topology graph">
              {t.data.edges.map((e, i) => {
                const a = layout.pos[e.from];
                const b = layout.pos[e.to];
                if (!a || !b) return null;
                const st = EDGE_STYLE[e.kind] ?? EDGE_STYLE.grpc;
                return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} strokeDasharray={st.dash}
                  className={cx(st.cls, "transition-opacity")} strokeWidth={linked(e) ? 2.2 : 1.2} opacity={focus && !linked(e) ? 0.15 : 0.8} />;
              })}
              {t.data.nodes.map((n) => {
                const p = layout.pos[n.id];
                if (!p) return null;
                const w = n.kind === "agent" ? 128 : 150;
                const dim = focus && focus !== n.id && !t.data!.edges.some((e) => (e.from === focus && e.to === n.id) || (e.to === focus && e.from === n.id));
                return (
                  <g key={n.id} transform={`translate(${p.x - w / 2},${p.y - 26})`} opacity={dim ? 0.35 : 1}
                    className="cursor-pointer" tabIndex={0} role="button" aria-label={`${n.label}, ${n.status}${n.leader ? ", leader" : ""}`}
                    onMouseEnter={() => setHover(n.id)} onMouseLeave={() => setHover(null)}
                    onFocus={() => setHover(n.id)} onBlur={() => setHover(null)}
                    onClick={() => setSel(n.id === sel ? null : n.id)}
                    onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setSel(n.id === sel ? null : n.id); } }}>
                    <rect width={w} height={52} rx={12} className={cx(n.leader ? "fill-accent-soft" : "fill-surface", statusClass(n.status))} strokeWidth={sel === n.id ? 3 : 1.5} />
                    <text x={w / 2} y={22} textAnchor="middle" className="fill-ink text-[12px] font-semibold">{n.leader ? "👑 " : ""}{n.label.length > 18 ? n.label.slice(0, 17) + "…" : n.label}</text>
                    <text x={w / 2} y={39} textAnchor="middle" className="fill-muted text-[10px]">
                      {n.kind === "agent" ? `${n.status.toLowerCase()} · r${n.appliedRevision}` : n.kind === "controller" ? `${n.status} · epoch ${n.epoch}` : `${n.kind} · ${n.status}`}
                    </text>
                  </g>
                );
              })}
            </svg>
            <ul className="mt-2 flex flex-wrap gap-x-5 gap-y-1 px-2 text-xs text-muted" aria-label="Legend">
              {Object.values(EDGE_STYLE).map((s) => (
                <li key={s.label} className="flex items-center gap-2">
                  <svg width="26" height="6" aria-hidden><line x1="0" y1="3" x2="26" y2="3" className={s.cls} strokeWidth={2} strokeDasharray={s.dash} /></svg>{s.label}
                </li>
              ))}
            </ul>
          </Card>
          <Card>
            {selected ? (
              <div>
                <p className="eyebrow">{selected.kind}</p>
                <h2 className="mt-1 break-all font-mono text-base font-semibold">{selected.label}</h2>
                <div className="mt-2"><StatusBadge value={selected.status} /></div>
                <dl className="mt-4 space-y-2 text-sm">
                  {selected.leader !== undefined && <div className="flex justify-between"><dt className="text-muted">Leader</dt><dd>{selected.leader ? "yes" : "no"}</dd></div>}
                  {selected.epoch !== undefined && <div className="flex justify-between"><dt className="text-muted">Epoch</dt><dd>{selected.epoch}</dd></div>}
                  {selected.env && <div className="flex justify-between"><dt className="text-muted">Environment</dt><dd>{selected.env}</dd></div>}
                  {selected.appliedRevision !== undefined && <div className="flex justify-between"><dt className="text-muted">Applied revision</dt><dd>r{selected.appliedRevision}</dd></div>}
                  {selected.faults && selected.faults.length > 0 && <div className="flex justify-between"><dt className="text-muted">Faults</dt><dd>{selected.faults.join(", ")}</dd></div>}
                  <div><dt className="text-muted">Connections</dt>
                    <dd><ul className="mt-1 space-y-1">{t.data.edges.filter((e) => e.from === selected.id || e.to === selected.id).map((e, i) => (
                      <li key={i} className="font-mono text-xs">{e.from === selected.id ? `→ ${e.to}` : `← ${e.from}`} <span className="text-muted">({e.kind})</span></li>
                    ))}</ul></dd>
                  </div>
                </dl>
              </div>
            ) : (
              <div className="text-sm text-muted">
                <p className="font-medium text-ink">Select a node</p>
                <p className="mt-2">{t.data.nodes.filter((n) => n.kind === "controller").length} controllers, {t.data.nodes.filter((n) => n.kind === "agent").length} agents. Each controller keeps its own copy of the state in a JSON file; the leader replicates it to the others.</p>
                <p className="mt-2">Agents always talk to the current leader, so their gRPC edges move to the new leader after a failover.</p>
              </div>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

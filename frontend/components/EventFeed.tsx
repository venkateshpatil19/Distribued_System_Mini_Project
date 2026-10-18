"use client";

import { useMemo, useState } from "react";
import { api, type DgEvent } from "@/lib/api";
import { describe, eventTone } from "@/lib/events";
import { relativeTime } from "@/lib/format";
import { useEventStream, usePoll } from "@/lib/hooks";
import { Badge, EmptyState, ErrorState, LoadingBlock, SimulatedTag, cx } from "./ui";

const FILTERS = [
  { key: "all", label: "All" },
  { key: "ROLLOUT", label: "Rollouts" },
  { key: "LEADER", label: "Elections" },
  { key: "AGENT", label: "Agents" },
  { key: "CONFIG", label: "Config" },
  { key: "FAULT", label: "Faults" },
];

// Merges stored history with the live SSE stream (deduplicated by event id).
export function EventFeed({ limit = 40, compact = false }: { limit?: number; compact?: boolean }) {
  const history = usePoll(() => api<DgEvent[]>(`/events?limit=${limit}`), 15000);
  const { events: live, status } = useEventStream(limit);
  const [filter, setFilter] = useState("all");
  const [hideApplied, setHideApplied] = useState(true);

  const merged = useMemo(() => {
    const seen = new Set<string>();
    const out: DgEvent[] = [];
    for (const e of [...live, ...(history.data ?? [])]) {
      if (seen.has(e.eventId)) continue;
      seen.add(e.eventId);
      out.push(e);
    }
    return out
      .filter((e) => filter === "all" || e.type.startsWith(filter) || (filter === "LEADER" && e.type.startsWith("ELECTION")))
      .filter((e) => !hideApplied || filter === "CONFIG" || e.type !== "CONFIG_APPLIED")
      .slice(0, limit);
  }, [live, history.data, filter, hideApplied, limit]);

  const statusTone = status === "live" ? "ok" : status === "offline" ? "bad" : "warn";
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Badge tone={statusTone}><span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />{status === "live" ? "streaming" : status}</Badge>
        {!compact && (
          <div role="group" aria-label="Filter events" className="flex flex-wrap gap-1">
            {FILTERS.map((f) => (
              <button key={f.key} type="button" aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}
                className={cx("rounded-lg px-2.5 py-1 text-xs", filter === f.key ? "bg-accent-soft font-medium text-accent" : "text-muted hover:bg-surface-2")}>
                {f.label}
              </button>
            ))}
          </div>
        )}
        <label className="ml-auto flex items-center gap-1.5 text-xs text-muted">
          <input type="checkbox" checked={hideApplied} onChange={(e) => setHideApplied(e.target.checked)} className="accent-[rgb(var(--accent))]" />
          Hide per-agent acks
        </label>
      </div>
      {history.loading && !history.data && <LoadingBlock label="Loading events" />}
      {history.error && !history.data && <ErrorState message={history.error} onRetry={history.refresh} />}
      {history.data && merged.length === 0 && <EmptyState title="No events yet">Events appear here as controllers and agents act.</EmptyState>}
      <ol className="space-y-1.5">
        {merged.map((e) => (
          <li key={e.eventId} className={cx("flex items-start gap-3 rounded-xl border-l-2 bg-surface-2/50 px-3 py-2",
            { ok: "border-l-ok", warn: "border-l-warn", bad: "border-l-bad", info: "border-l-info", neutral: "border-l-line" }[eventTone(e)])}>
            <div className="min-w-0 flex-1">
              <p className="text-sm">{describe(e)}</p>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
                <span className="font-mono">{e.type}</span>
                <span>{e.nodeId}</span>
                <span title="Lamport timestamp">L={e.lamport}</span>
                <time dateTime={e.wallTs} title={e.wallTs}>{relativeTime(e.recordedAt ?? e.wallTs)}</time>
                {e.simulated && <SimulatedTag />}
              </p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

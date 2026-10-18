"use client";

import Link from "next/link";
import { Crown, GitCommitHorizontal, Server } from "lucide-react";
import { api, type Overview } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import { LastUpdated, MetricCard, Skeleton } from "@/components/ui";

// Three live metric cards on the overview page. Falls back to a clear message when no cluster is reachable.
export function LiveStrip() {
  const { data, error, loading, refreshedAt } = usePoll(() => api<Overview>("/overview"), 5000);
  if (loading && !data) {
    return (
      <div className="grid gap-4 sm:grid-cols-3">
        {[0, 1, 2].map((i) => <Skeleton key={i} className="h-[76px]" />)}
      </div>
    );
  }
  if (!data) {
    return (
      <div className="card p-4 text-sm text-muted">
        No control plane is reachable from this page right now{error ? ` (${error})` : ""}. Run start.bat (or scripts/run-local.sh), then open the <Link className="text-accent underline" href="/dashboard">dashboard</Link>.
      </div>
    );
  }
  return (
    <div>
      <div className="grid gap-4 sm:grid-cols-3">
        <MetricCard icon={<Crown className="h-5 w-5" />} label="Current leader" value={data.leader ?? "electing"} hint={`epoch ${data.epoch}`} />
        <MetricCard icon={<Server className="h-5 w-5" />} label="Live agents" value={`${data.agents.live} / ${data.agents.total}`} hint={`${data.agents.converged} on latest revision`} tone="info" />
        <MetricCard icon={<GitCommitHorizontal className="h-5 w-5" />} label="Latest revision" value={`r${data.latestRevision}`} hint={`${data.activeRollouts.length} active rollout${data.activeRollouts.length === 1 ? "" : "s"}`} tone="ok" />
      </div>
      <div className="mt-2 flex justify-end"><LastUpdated at={refreshedAt} label="Live, updated" /></div>
    </div>
  );
}

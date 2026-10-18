"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Flag as FlagIcon, Plus, Save, Search, Trash2, Wand2 } from "lucide-react";
import { api, patch, post, put, type EnvConfig, type Flag, type Rule } from "@/lib/api";
import { relativeTime, showValue } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { Badge, Card, EmptyState, ErrorState, LoadingBlock, Modal, OfflineBanner, PageHeader, SectionTitle, SimulatedTag, cx, useToast } from "@/components/ui";

type Env = { key: string; name: string };
const OPERATORS = ["equals", "not_equals", "in", "not_in", "contains", "starts_with", "gt", "lt"];

export function FlagsClient() {
  const params = useSearchParams();
  const router = useRouter();
  const flags = usePoll(() => api<Flag[]>("/flags"), 8000);
  const envs = usePoll(() => api<Env[]>("/environments"), 0);
  const [q, setQ] = useState("");
  const [env, setEnv] = useState("production");
  const [creating, setCreating] = useState(false);
  const [addingEnv, setAddingEnv] = useState(false);
  const selected = params.get("flag");

  const list = useMemo(() => {
    const t = q.trim().toLowerCase();
    return (flags.data ?? []).filter((f) => !t || `${f.key} ${f.name} ${f.description} ${f.type}`.toLowerCase().includes(t));
  }, [flags.data, q]);
  const current = flags.data?.find((f) => f.key === selected) ?? null;

  const select = (key: string) => router.replace(`/flags?flag=${encodeURIComponent(key)}`, { scroll: false });

  return (
    <div className="page py-10">
      <PageHeader eyebrow="Flags" title="Feature flags and" accent="targeting"
        right={<div className="flex gap-2">
          <button className="btn-ghost" onClick={() => setAddingEnv(true)}>Add environment</button>
          <button className="btn-primary" onClick={() => setCreating(true)}><Plus className="h-4 w-4" aria-hidden /> New flag</button>
        </div>}>
        Every change becomes a new global revision that agents apply in order. Edits are blocked while a rollout owns the flag.
      </PageHeader>
      <OfflineBanner />

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <label className="relative min-w-[14rem] flex-1">
          <span className="sr-only">Search flags</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" aria-hidden />
          <input className="input pl-9" placeholder="Search by key, name, type or description" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted">Environment</span>
          <select className="input !w-auto" aria-label="Environment" value={env} onChange={(e) => setEnv(e.target.value)}>
            {(envs.data ?? [{ key: "production", name: "Production" }]).map((e) => <option key={e.key} value={e.key}>{e.name}</option>)}
          </select>
        </label>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <div>
          {flags.loading && !flags.data && <LoadingBlock label="Loading flags" />}
          {flags.error && !flags.data && <ErrorState message={flags.error} onRetry={flags.refresh} />}
          {flags.data && list.length === 0 && <EmptyState title={q ? "No flags match" : "No flags yet"}>{q ? "Try a different search." : "Create your first flag."}</EmptyState>}
          <ul className="space-y-2">
            {list.map((f) => {
              const c = f.envs[env];
              const active = f.activeRollouts?.find((r) => r.envKey === env);
              return (
                <li key={f.key}>
                  <button type="button" onClick={() => select(f.key)} aria-current={selected === f.key}
                    className={cx("w-full rounded-2xl border p-3 text-left transition", selected === f.key ? "border-accent bg-accent-soft/60" : "border-line bg-surface hover:border-accent/50")}>
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium">{f.name}</span>
                      <Badge tone={c?.enabled ? "ok" : "neutral"}>{c?.enabled ? `on · ${c.rolloutPercent}%` : "off"}</Badge>
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted">
                      <span className="font-mono">{f.key}</span><span>{f.type}</span>
                      {c && c.rules.length > 0 && <span>{c.rules.length} rule{c.rules.length > 1 ? "s" : ""}</span>}
                      {active && <Badge tone="warn">rollout #{active.id}</Badge>}
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
        <div className="min-w-0">
          {current ? <FlagDetail key={`${current.key}-${env}`} flag={current} env={env} onChanged={flags.refresh} /> : (
            <Card><EmptyState title="Select a flag">Choose a flag to edit its configuration, preview exposure, explain an evaluation or browse revisions.</EmptyState></Card>
          )}
        </div>
      </div>

      <CreateFlagModal open={creating} onClose={() => setCreating(false)} onCreated={(k) => { flags.refresh(); select(k); }} />
      <CreateEnvModal open={addingEnv} onClose={() => setAddingEnv(false)} onCreated={() => { envs.refresh(); flags.refresh(); }} />
    </div>
  );
}

function FlagDetail({ flag, env, onChanged }: { flag: Flag; env: string; onChanged: () => void }) {
  const toast = useToast();
  const base = flag.envs[env];
  const active = flag.activeRollouts?.find((r) => r.envKey === env);
  const [cfg, setCfg] = useState<EnvConfig | null>(base ? { ...base, rules: base.rules.map((r) => ({ ...r, values: [...r.values] })) } : null);
  const [onText, setOnText] = useState(base?.onValue ?? "");
  const [offText, setOffText] = useState(base?.offValue ?? "");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [meta, setMeta] = useState({ name: flag.name, description: flag.description });
  // Bumped after each save so the revision list reloads at once.
  const [savedCount, setSavedCount] = useState(0);

  if (!cfg) return <Card><ErrorState message={`Flag ${flag.key} has no configuration for ${env}.`} /></Card>;

  const draft = () => {
    let on: unknown;
    let off: unknown;
    try {
      on = JSON.parse(onText);
      off = JSON.parse(offText);
    } catch {
      throw new Error("Values must be valid JSON, for example true, 12, \"blue\" or {\"a\":1}");
    }
    return { enabled: cfg.enabled, rolloutPercent: cfg.rolloutPercent, rules: cfg.rules, onValue: on, offValue: off };
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const r = await put<{ revision: number }>(`/flags/${flag.key}/envs/${env}`, { ...draft(), reason: reason || "edited in dashboard" });
      toast("success", `Saved as revision r${r.revision}; agents are applying it now.`);
      setReason("");
      setSavedCount((n) => n + 1);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const saveMeta = async () => {
    try {
      await patch(`/flags/${flag.key}`, meta);
      toast("success", "Flag details updated.");
      onChanged();
    } catch (e) {
      toast("error", e instanceof Error ? e.message : String(e));
    }
  };

  const setRule = (i: number, r: Partial<Rule>) => setCfg({ ...cfg, rules: cfg.rules.map((x, j) => (j === i ? { ...x, ...r } : x)) });

  return (
    <div className="space-y-6">
      <Card accent={cfg.enabled ? "accent" : undefined}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="flex items-center gap-2 text-lg font-semibold"><FlagIcon className="h-4 w-4 text-accent" aria-hidden /> {flag.name}</h2>
            <p className="mt-1 font-mono text-xs text-muted">{flag.key} · {flag.type} · {env} · updated {relativeTime(base?.updatedAt ?? flag.updatedAt)}</p>
          </div>
          {active && <Link href={`/rollouts?id=${active.id}`} className="chip border-warn/40 bg-warn/10 text-warn">Rollout #{active.id} owns this flag</Link>}
        </div>

        <fieldset disabled={!!active || saving} className="mt-5 grid gap-5 disabled:opacity-60">
          <legend className="sr-only">Configuration for {env}</legend>
          <div className="flex flex-wrap items-center gap-6">
            <label className="flex items-center gap-2 text-sm font-medium">
              <input type="checkbox" checked={cfg.enabled} onChange={(e) => setCfg({ ...cfg, enabled: e.target.checked })} className="h-4 w-4 accent-[rgb(var(--accent))]" />
              Enabled in {env}
            </label>
            <label className="flex min-w-[16rem] flex-1 items-center gap-3 text-sm">
              <span className="text-muted">Rollout</span>
              <input type="range" min={0} max={100} value={cfg.rolloutPercent} onChange={(e) => setCfg({ ...cfg, rolloutPercent: Number(e.target.value) })} className="flex-1 accent-[rgb(var(--accent))]" aria-label="Rollout percentage" />
              <span className="w-12 text-right font-semibold tabular-nums">{cfg.rolloutPercent}%</span>
            </label>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <label><span className="label">Value when on (JSON)</span><input className="input font-mono" value={onText} onChange={(e) => setOnText(e.target.value)} /></label>
            <label><span className="label">Value when off (JSON)</span><input className="input font-mono" value={offText} onChange={(e) => setOffText(e.target.value)} /></label>
          </div>

          <div>
            <div className="mb-2 flex items-center justify-between">
              <p className="text-sm font-medium">Targeting rules <span className="font-normal text-muted">(first match wins, before the percentage)</span></p>
              <button type="button" className="btn-ghost !py-1 text-xs" onClick={() => setCfg({ ...cfg, rules: [...cfg.rules, { attribute: "country", operator: "in", values: ["IN"], serve: "on" }] })}>
                <Plus className="h-3.5 w-3.5" aria-hidden /> Add rule
              </button>
            </div>
            {cfg.rules.length === 0 && <p className="text-sm text-muted">No rules. Everyone is bucketed by the percentage.</p>}
            <ul className="space-y-2">
              {cfg.rules.map((r, i) => (
                <li key={i} className="grid gap-2 rounded-xl border border-line p-2 sm:grid-cols-[1fr_1fr_1.4fr_auto_auto] sm:items-center">
                  <input className="input" aria-label={`Rule ${i + 1} attribute`} value={r.attribute} onChange={(e) => setRule(i, { attribute: e.target.value })} placeholder="attribute" />
                  <select className="input" aria-label={`Rule ${i + 1} operator`} value={r.operator} onChange={(e) => setRule(i, { operator: e.target.value })}>
                    {OPERATORS.map((o) => <option key={o} value={o}>{o.replace("_", " ")}</option>)}
                  </select>
                  <input className="input" aria-label={`Rule ${i + 1} values, comma separated`} value={r.values.join(", ")} onChange={(e) => setRule(i, { values: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} placeholder="values, comma separated" />
                  <select className="input" aria-label={`Rule ${i + 1} serves`} value={r.serve} onChange={(e) => setRule(i, { serve: e.target.value as "on" | "off" })}>
                    <option value="on">serve on</option>
                    <option value="off">serve off</option>
                  </select>
                  <button type="button" className="btn-ghost !px-2" aria-label={`Remove rule ${i + 1}`} onClick={() => setCfg({ ...cfg, rules: cfg.rules.filter((_, j) => j !== i) })}><Trash2 className="h-4 w-4" /></button>
                </li>
              ))}
            </ul>
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <label className="min-w-[14rem] flex-1"><span className="label">Reason (recorded in the audit trail)</span><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why are you changing this?" maxLength={200} /></label>
            <button className="btn-primary" onClick={save} type="button"><Save className="h-4 w-4" aria-hidden /> {saving ? "Saving..." : "Save as new revision"}</button>
          </div>
        </fieldset>
        {error && <div className="mt-3"><ErrorState message={error} /></div>}
      </Card>

      <div className="grid gap-6 xl:grid-cols-2">
        <PreviewCard flagKey={flag.key} env={env} draft={draft} />
        <EvaluateCard flagKey={flag.key} env={env} draft={draft} />
      </div>

      <Card>
        <SectionTitle emoji="✏️" title="Details" />
        <div className="grid gap-3 sm:grid-cols-[1fr_2fr_auto] sm:items-end">
          <label><span className="label">Name</span><input className="input" value={meta.name} maxLength={120} onChange={(e) => setMeta({ ...meta, name: e.target.value })} /></label>
          <label><span className="label">Description</span><input className="input" value={meta.description} maxLength={1000} onChange={(e) => setMeta({ ...meta, description: e.target.value })} /></label>
          <button className="btn-ghost" type="button" onClick={saveMeta}>Update</button>
        </div>
      </Card>

      <Revisions flagKey={flag.key} reloadKey={savedCount} />
    </div>
  );
}

function PreviewCard({ flagKey, env, draft }: { flagKey: string; env: string; draft: () => unknown }) {
  const [res, setRes] = useState<{ onPercent: number; sampleSize: number; reasons: Record<string, number> } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const run = async () => {
    setErr(null);
    try {
      setRes(await post(`/flags/${flagKey}/preview`, { env, sampleSize: 5000, config: draft() }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Card>
      <SectionTitle emoji="🔭" title="Preview exposure" action={<button className="btn-ghost !py-1 text-xs" type="button" onClick={run}><Wand2 className="h-3.5 w-3.5" aria-hidden /> Run preview</button>}>
        Evaluates the unsaved draft for 5,000 synthetic users.
      </SectionTitle>
      {err && <ErrorState message={err} />}
      {res ? (
        <div>
          <p className="text-3xl font-semibold tabular-nums">{res.onPercent}%<span className="ml-2 text-sm font-normal text-muted">would get the on variant</span></p>
          <div className="mt-2"><SimulatedTag /></div>
          <ul className="mt-3 space-y-1 text-sm">
            {Object.entries(res.reasons).map(([k, n]) => (
              <li key={k} className="flex justify-between gap-2"><span className="text-muted">{k.replace(/_/g, " ").toLowerCase()}</span><span className="tabular-nums">{n}</span></li>
            ))}
          </ul>
        </div>
      ) : !err && <p className="text-sm text-muted">Run a preview to see how many users the draft would reach.</p>}
    </Card>
  );
}

function EvaluateCard({ flagKey, env, draft }: { flagKey: string; env: string; draft: () => unknown }) {
  const [ctx, setCtx] = useState({ userId: "u42", country: "IN", plan: "pro" });
  const [useDraft, setUseDraft] = useState(false);
  const [res, setRes] = useState<{ variant: string; value: string; reason: string; bucket: number } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => setRes(null), [env]);
  const run = async () => {
    setErr(null);
    try {
      setRes(await post(`/flags/${flagKey}/evaluate`, { env, context: ctx, ...(useDraft ? { config: draft() } : {}) }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Card>
      <SectionTitle emoji="🧪" title="Explain an evaluation">See exactly why a user gets a variant.</SectionTitle>
      <div className="grid grid-cols-3 gap-2">
        {(["userId", "country", "plan"] as const).map((k) => (
          <label key={k}><span className="label">{k}</span><input className="input" value={ctx[k]} onChange={(e) => setCtx({ ...ctx, [k]: e.target.value })} /></label>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <label className="flex items-center gap-2 text-xs text-muted"><input type="checkbox" checked={useDraft} onChange={(e) => setUseDraft(e.target.checked)} /> Use unsaved draft</label>
        <button className="btn-ghost !py-1 text-xs" type="button" onClick={run}>Evaluate</button>
      </div>
      {err && <div className="mt-3"><ErrorState message={err} /></div>}
      {res && (
        <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">Variant</dt><dd><Badge tone={res.variant === "on" ? "ok" : "neutral"}>{res.variant}</Badge></dd>
          <dt className="text-muted">Value</dt><dd className="font-mono">{showValue(res.value)}</dd>
          <dt className="text-muted">Reason</dt><dd>{res.reason}</dd>
          <dt className="text-muted">Bucket</dt><dd className="tabular-nums">{res.bucket < 0 ? "none (no userId)" : `${res.bucket} of 10,000`}</dd>
        </dl>
      )}
    </Card>
  );
}

type Revision = { revision: number; createdAt: string; reason: string; actor: string; envKey: string | null; fenceToken: number | null; beforeConfig: EnvConfig | null; afterConfig: EnvConfig | null };

function Revisions({ flagKey, reloadKey }: { flagKey: string; reloadKey: number }) {
  const revs = usePoll(() => api<Revision[]>(`/flags/${flagKey}/revisions?limit=25`), 10000, [flagKey, reloadKey]);
  const summary = (b: EnvConfig | null, a: EnvConfig | null) => {
    if (!a) return "created";
    if (!b) return `enabled=${a.enabled}, ${a.rolloutPercent}%`;
    const parts: string[] = [];
    if (b.enabled !== a.enabled) parts.push(`enabled ${b.enabled} → ${a.enabled}`);
    if (b.rolloutPercent !== a.rolloutPercent) parts.push(`${b.rolloutPercent}% → ${a.rolloutPercent}%`);
    if (JSON.stringify(b.rules) !== JSON.stringify(a.rules)) parts.push(`rules ${b.rules.length} → ${a.rules.length}`);
    if (b.onValue !== a.onValue) parts.push(`on value ${showValue(b.onValue)} → ${showValue(a.onValue)}`);
    if (b.offValue !== a.offValue) parts.push(`off value changed`);
    return parts.join(", ") || "no change (re-published)";
  };
  return (
    <Card>
      <SectionTitle emoji="🗂️" title="Revision history">Global revisions that touched this flag, newest first. Fencing tokens show which lease holder wrote each one.</SectionTitle>
      {revs.error && !revs.data && <ErrorState message={revs.error} onRetry={revs.refresh} />}
      {revs.loading && !revs.data && <LoadingBlock />}
      {revs.data && revs.data.length === 0 && <EmptyState title="No revisions yet" />}
      {revs.data && revs.data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Revision</th><th>Env</th><th>Change</th><th>Reason</th><th>By</th><th>Token</th><th>When</th></tr></thead>
            <tbody>
              {revs.data.map((r) => (
                <tr key={r.revision}>
                  <td className="font-mono">r{r.revision}</td>
                  <td>{r.envKey ?? "all"}</td>
                  <td>{summary(r.beforeConfig, r.afterConfig)}</td>
                  <td className="max-w-[16rem] text-muted">{r.reason}</td>
                  <td className="text-muted">{r.actor}</td>
                  <td className="font-mono text-muted">{r.fenceToken ?? "n/a"}</td>
                  <td className="whitespace-nowrap text-muted"><time dateTime={r.createdAt} title={r.createdAt}>{relativeTime(r.createdAt)}</time></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function CreateFlagModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (key: string) => void }) {
  const toast = useToast();
  const [f, setF] = useState({ key: "", name: "", description: "", type: "boolean", onValue: "true", offValue: "false" });
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const defaults: Record<string, [string, string]> = { boolean: ["true", "false"], string: ['"new"', '"old"'], number: ["10", "0"], json: ['{"enabled":true}', "{}"] };
  const submit = async () => {
    setErr(null);
    if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(f.key)) return setErr("Key must be 2-63 lowercase letters, digits or hyphens.");
    let on: unknown;
    let off: unknown;
    try {
      on = JSON.parse(f.onValue);
      off = JSON.parse(f.offValue);
    } catch {
      return setErr("Values must be valid JSON.");
    }
    setBusy(true);
    try {
      await post("/flags", { key: f.key, name: f.name, description: f.description, type: f.type, onValue: on, offValue: off });
      toast("success", `Flag ${f.key} created (disabled in every environment).`);
      onCreated(f.key);
      onClose();
      setF({ key: "", name: "", description: "", type: "boolean", onValue: "true", offValue: "false" });
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="New feature flag"
      footer={<><button className="btn-ghost" onClick={onClose}>Cancel</button><button className="btn-primary" onClick={submit} disabled={busy}>{busy ? "Creating..." : "Create flag"}</button></>}>
      <div className="grid gap-3">
        <label><span className="label">Key</span><input className="input font-mono" value={f.key} onChange={(e) => setF({ ...f, key: e.target.value.toLowerCase() })} placeholder="dark-mode" /></label>
        <label><span className="label">Name</span><input className="input" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="Dark mode" /></label>
        <label><span className="label">Description</span><input className="input" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></label>
        <label><span className="label">Type</span>
          <select className="input" aria-label="Type" value={f.type} onChange={(e) => { const t = e.target.value; setF({ ...f, type: t, onValue: defaults[t][0], offValue: defaults[t][1] }); }}>
            {Object.keys(defaults).map((t) => <option key={t}>{t}</option>)}
          </select>
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label><span className="label">On value (JSON)</span><input className="input font-mono" value={f.onValue} onChange={(e) => setF({ ...f, onValue: e.target.value })} /></label>
          <label><span className="label">Off value (JSON)</span><input className="input font-mono" value={f.offValue} onChange={(e) => setF({ ...f, offValue: e.target.value })} /></label>
        </div>
        {err && <ErrorState message={err} />}
      </div>
    </Modal>
  );
}

function CreateEnvModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const toast = useToast();
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    setErr(null);
    try {
      await post("/environments", { key, name });
      toast("success", `Environment ${key} created; every flag starts disabled there.`);
      onCreated();
      onClose();
      setKey("");
      setName("");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="Add environment"
      footer={<><button className="btn-ghost" onClick={onClose}>Cancel</button><button className="btn-primary" onClick={submit}>Create</button></>}>
      <div className="grid gap-3">
        <p className="text-muted">Agents choose their environment at start-up with <code className="font-mono">DG_ENV</code>.</p>
        <label><span className="label">Key</span><input className="input font-mono" value={key} onChange={(e) => setKey(e.target.value.toLowerCase())} placeholder="qa" /></label>
        <label><span className="label">Name</span><input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="QA" /></label>
        {err && <ErrorState message={err} />}
      </div>
    </Modal>
  );
}

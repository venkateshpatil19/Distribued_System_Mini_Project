// Pure formatting helpers (unit tested in tests/format.test.ts).

export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return "never";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "unknown";
  const s = Math.round((now - t) / 1000);
  if (s < -5) return "in the future";
  if (s < 5) return "just now";
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} days ago`;
}

export function ms(n: number | null | undefined): string {
  if (n === null || n === undefined || Number.isNaN(n)) return "n/a";
  if (Math.abs(n) < 1000) return `${Math.round(n)} ms`;
  return `${(n / 1000).toFixed(n < 10_000 ? 2 : 1)} s`;
}

export function pct(rate: number | null | undefined, digits = 1): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return "n/a";
  return `${(rate * 100).toFixed(digits)}%`;
}

export function compact(n: number | null | undefined): string {
  if (n === null || n === undefined) return "0";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 10_000) return `${Math.round(n / 1000)}k`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

// Pretty-prints a stored JSON-encoded flag value for display.
export function showValue(json: string | null | undefined): string {
  if (json === null || json === undefined) return "null";
  try {
    const v = JSON.parse(json);
    return typeof v === "string" ? `"${v}"` : JSON.stringify(v);
  } catch {
    return json;
  }
}

export type VcRelation = "BEFORE" | "AFTER" | "EQUAL" | "CONCURRENT";

// Same rule as the backend VectorClock.compare; used to explain events client-side.
export function compareVc(a: Record<string, number>, b: Record<string, number>): VcRelation {
  const keys = new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})]);
  let aLess = false;
  let bLess = false;
  for (const k of keys) {
    const x = a?.[k] ?? 0;
    const y = b?.[k] ?? 0;
    if (x < y) aLess = true;
    if (y < x) bLess = true;
  }
  if (aLess && bLess) return "CONCURRENT";
  if (aLess) return "BEFORE";
  if (bLess) return "AFTER";
  return "EQUAL";
}

export function stageLabel(stages: number[], current: number): string {
  if (!stages?.length) return "n/a";
  return `stage ${current + 1} of ${stages.length} (${stages[current]}%)`;
}

export function titleCase(s: string): string {
  return s.toLowerCase().replace(/(^|_)(\w)/g, (_, sep: string, c: string) => (sep ? " " : "") + c.toUpperCase());
}

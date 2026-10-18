"use client";

import { useState } from "react";

export type Series = { name: string; color: string; values: Array<number | null> };

// Responsive multi-series line chart in plain SVG. Labels sit on the x axis; values may contain gaps (null).
export function LineChart({ labels, series, height = 180, format = (v: number) => String(Math.round(v)), title }: {
  labels: string[]; series: Series[]; height?: number; format?: (v: number) => string; title: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 600;
  const H = height;
  const pad = { l: 44, r: 10, t: 10, b: 22 };
  const all = series.flatMap((s) => s.values.filter((v): v is number => v !== null && Number.isFinite(v)));
  const max = Math.max(1e-9, ...all, 0);
  const n = Math.max(labels.length, 1);
  const x = (i: number) => pad.l + (n === 1 ? (W - pad.l - pad.r) / 2 : (i * (W - pad.l - pad.r)) / (n - 1));
  const y = (v: number) => pad.t + (H - pad.t - pad.b) * (1 - v / max);
  if (!labels.length || !all.length) {
    return <div className="grid h-40 place-items-center rounded-xl border border-dashed border-line text-sm text-muted">No data in this window yet</div>;
  }
  const ticks = [0, max / 2, max];
  return (
    <figure>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" role="img" aria-label={title}
        onMouseLeave={() => setHover(null)}
        onMouseMove={(e) => {
          const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          const px = ((e.clientX - r.left) / r.width) * W;
          const i = Math.round(((px - pad.l) / (W - pad.l - pad.r)) * (n - 1));
          setHover(Math.max(0, Math.min(n - 1, i)));
        }}>
        {ticks.map((t, i) => (
          <g key={i}>
            <line x1={pad.l} x2={W - pad.r} y1={y(t)} y2={y(t)} className="stroke-line" strokeDasharray="3 4" />
            <text x={pad.l - 6} y={y(t) + 4} textAnchor="end" className="fill-muted text-[10px]">{format(t)}</text>
          </g>
        ))}
        {[0, Math.floor((n - 1) / 2), n - 1].filter((v, i, a) => a.indexOf(v) === i).map((i) => (
          <text key={i} x={x(i)} y={H - 6} textAnchor="middle" className="fill-muted text-[10px]">{labels[i]}</text>
        ))}
        {series.map((s) => {
          let d = "";
          s.values.forEach((v, i) => {
            if (v === null || !Number.isFinite(v)) return;
            d += `${d === "" || s.values[i - 1] === null ? "M" : "L"}${x(i).toFixed(1)},${y(v).toFixed(1)} `;
          });
          return <path key={s.name} d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />;
        })}
        {hover !== null && (
          <g>
            <line x1={x(hover)} x2={x(hover)} y1={pad.t} y2={H - pad.b} className="stroke-muted" strokeWidth={0.5} />
            {series.map((s) => {
              const v = s.values[hover];
              return v === null || v === undefined ? null : <circle key={s.name} cx={x(hover)} cy={y(v)} r={3.5} fill={s.color} />;
            })}
          </g>
        )}
      </svg>
      <figcaption className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted">
        {series.map((s) => (
          <span key={s.name} className="flex items-center gap-1.5">
            <span className="h-2 w-3 rounded-sm" style={{ background: s.color }} aria-hidden /> {s.name}
            {hover !== null && s.values[hover] !== null && s.values[hover] !== undefined && <span className="font-medium text-ink tabular-nums">{format(s.values[hover] as number)}</span>}
          </span>
        ))}
        {hover !== null && <span className="ml-auto tabular-nums">{labels[hover]}</span>}
      </figcaption>
    </figure>
  );
}

// Horizontal bars for comparing a handful of values (experiment runs).
export function BarList({ rows, format, title }: { rows: Array<{ label: string; value: number | null; tone?: string }>; format: (v: number) => string; title: string }) {
  const max = Math.max(1, ...rows.map((r) => r.value ?? 0));
  return (
    <ul aria-label={title} className="space-y-2">
      {rows.map((r, i) => (
        <li key={i} className="grid grid-cols-[minmax(0,9rem)_1fr_auto] items-center gap-3 text-xs">
          <span className="truncate text-muted" title={r.label}>{r.label}</span>
          <span className="h-2.5 overflow-hidden rounded-full bg-surface-2">
            <span className="block h-full rounded-full" style={{ width: `${((r.value ?? 0) / max) * 100}%`, background: r.tone ?? "rgb(var(--accent))" }} />
          </span>
          <span className="tabular-nums">{r.value === null ? "n/a" : format(r.value)}</span>
        </li>
      ))}
    </ul>
  );
}

export const C = {
  accent: "rgb(var(--accent))",
  info: "rgb(var(--info))",
  bad: "rgb(var(--bad))",
  warn: "rgb(var(--warn))",
  muted: "rgb(var(--muted))",
};

"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  ArrowUp, Command, HelpCircle, LifeBuoy, Menu, Moon, Search, ShieldCheck, Sun, X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { NAV } from "@/lib/site";
import { api, type Flag } from "@/lib/api";
import { CopyButton, cx } from "./ui";
import buildInfo from "@/lib/build-info.json";

// ---------- theme ----------

// Runs before paint (inlined in <head>) so the page never flashes the wrong theme.
export const THEME_SCRIPT = `(function(){try{var t=localStorage.getItem('dg-theme');if(t!=='light'&&t!=='dark'){t=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}document.documentElement.setAttribute('data-theme',t)}catch(e){document.documentElement.setAttribute('data-theme','light')}})();`;

export function ThemeToggle() {
  const [theme, setTheme] = useState<"light" | "dark" | null>(null);
  useEffect(() => {
    setTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light");
  }, []);
  const flip = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try {
      localStorage.setItem("dg-theme", next);
    } catch {
      // Storage may be blocked; the theme still applies for this visit.
    }
    setTheme(next);
  };
  return (
    <button type="button" onClick={flip} className="grid h-9 w-9 place-items-center rounded-xl border border-line bg-surface text-ink transition hover:border-accent/60"
      aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"} title="Toggle theme">
      {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  );
}

// ---------- header ----------

export function Header() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <header className="sticky top-0 z-50 border-b border-line/70 bg-bg/80 backdrop-blur-md">
      <div className="page flex h-16 items-center gap-3">
        <Link href="/" className="flex items-center gap-2 font-semibold" aria-label="DeployGuard home">
          <span className="grid h-8 w-8 place-items-center rounded-xl bg-accent text-accent-ink"><ShieldCheck className="h-4 w-4" aria-hidden /></span>
          <span>DeployGuard</span>
        </Link>
        <nav aria-label="Main" className="ml-6 hidden items-center gap-1 lg:flex">
          {NAV.slice(1).map((n) => {
            const active = pathname === n.href;
            return (
              <Link key={n.href} href={n.href} aria-current={active ? "page" : undefined}
                className={cx("rounded-lg px-3 py-1.5 text-sm transition-colors", active ? "bg-accent-soft font-medium text-accent" : "text-muted hover:bg-surface-2 hover:text-ink")}>
                {n.label}
              </Link>
            );
          })}
        </nav>
        <div className="ml-auto flex items-center gap-2">
          <button type="button" onClick={() => window.dispatchEvent(new Event("dg:palette"))}
            className="hidden h-9 items-center gap-2 rounded-xl border border-line bg-surface px-3 text-sm text-muted transition hover:border-accent/60 sm:flex"
            aria-label="Search pages and flags (Control K)">
            <Search className="h-4 w-4" aria-hidden /> Search
            <kbd className="ml-2 rounded border border-line px-1.5 text-[10px]">Ctrl K</kbd>
          </button>
          <button type="button" onClick={() => window.dispatchEvent(new Event("dg:palette"))}
            className="grid h-9 w-9 place-items-center rounded-xl border border-line bg-surface sm:hidden" aria-label="Search">
            <Search className="h-4 w-4" />
          </button>
          <ThemeToggle />
          <button type="button" className="grid h-9 w-9 place-items-center rounded-xl border border-line bg-surface lg:hidden"
            aria-expanded={open} aria-controls="mobile-nav" aria-label={open ? "Close menu" : "Open menu"} onClick={() => setOpen((o) => !o)}>
            {open ? <X className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
          </button>
        </div>
      </div>
      {open && (
        <nav id="mobile-nav" aria-label="Mobile" className="border-t border-line bg-bg lg:hidden">
          <ul className="page grid gap-1 py-3">
            {NAV.map((n) => (
              <li key={n.href}>
                <Link href={n.href} aria-current={pathname === n.href ? "page" : undefined}
                  className={cx("flex flex-col rounded-xl px-3 py-2", pathname === n.href ? "bg-accent-soft text-accent" : "hover:bg-surface-2")}>
                  <span className="text-sm font-medium">{n.label}</span>
                  <span className="text-xs text-muted">{n.description}</span>
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </header>
  );
}

// ---------- command palette ----------

type PaletteItem = { id: string; label: string; hint: string; href: string; group: string; keywords: string };

export function CommandPalette() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const [flags, setFlags] = useState<Flag[] | null>(null);
  const [flagError, setFlagError] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLElement && (e.target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName));
      if ((e.key === "k" || e.key === "K") && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        setOpen((o) => !o);
      } else if (e.key === "/" && !typing) {
        e.preventDefault();
        setOpen(true);
      }
    };
    const onOpen = () => setOpen(true);
    document.addEventListener("keydown", onKey);
    window.addEventListener("dg:palette", onOpen);
    return () => {
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("dg:palette", onOpen);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    setQ("");
    setActive(0);
    setTimeout(() => inputRef.current?.focus(), 0);
    if (flags === null) {
      api<Flag[]>("/flags").then(setFlags).catch(() => setFlagError(true));
    }
  }, [open, flags]);

  const items = useMemo<PaletteItem[]>(() => {
    const pages: PaletteItem[] = NAV.map((n) => ({ id: n.href, label: n.label, hint: n.description, href: n.href, group: "Pages", keywords: n.keywords.join(" ") }));
    const actions: PaletteItem[] = [
      { id: "a-rollout", label: "Start a rollout", hint: "Open the rollout form", href: "/rollouts#new", group: "Actions", keywords: "canary launch stage" },
      { id: "a-fault", label: "Inject a fault", hint: "Partition, delay, crash or unhealthy traffic", href: "/agents#faults", group: "Actions", keywords: "chaos failure partition" },
      { id: "a-exp", label: "Run an experiment", hint: "Replayable fault scenarios", href: "/experiments#scenarios", group: "Actions", keywords: "replay compare recovery" },
      { id: "a-clock", label: "Compare two events", hint: "Lamport and vector clocks", href: "/experiments#clocks", group: "Actions", keywords: "causality happened before concurrent" },
    ];
    const fl: PaletteItem[] = (flags ?? []).map((f) => ({ id: `f-${f.key}`, label: f.name, hint: f.key, href: `/flags?flag=${encodeURIComponent(f.key)}`, group: "Flags", keywords: `${f.key} ${f.description}` }));
    const all = [...pages, ...actions, ...fl];
    const term = q.trim().toLowerCase();
    if (!term) return all;
    return all.filter((i) => `${i.label} ${i.hint} ${i.keywords}`.toLowerCase().includes(term));
  }, [q, flags]);

  const go = useCallback((i: PaletteItem | undefined) => {
    if (!i) return;
    setOpen(false);
    router.push(i.href);
  }, [router]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[90] flex items-start justify-center p-4 pt-[12vh]" role="presentation">
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={() => setOpen(false)} aria-hidden />
      <div role="dialog" aria-modal="true" aria-label="Search" className="card relative z-10 w-full max-w-xl overflow-hidden p-0">
        <div className="flex items-center gap-2 border-b border-line px-4">
          <Search className="h-4 w-4 text-muted" aria-hidden />
          <input ref={inputRef} value={q} onChange={(e) => { setQ(e.target.value); setActive(0); }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)); }
              else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
              else if (e.key === "Enter") { e.preventDefault(); go(items[active]); }
              else if (e.key === "Escape") setOpen(false);
            }}
            role="combobox" aria-expanded="true" aria-controls="palette-list" aria-activedescendant={items[active] ? `pi-${active}` : undefined}
            placeholder="Search pages, actions and flags" className="h-12 w-full bg-transparent text-sm outline-none" aria-label="Search query" />
          <kbd className="rounded border border-line px-1.5 text-[10px] text-muted">Esc</kbd>
        </div>
        <ul id="palette-list" role="listbox" className="max-h-[50vh] overflow-y-auto p-2">
          {items.length === 0 && <li className="px-3 py-6 text-center text-sm text-muted">No matches for &ldquo;{q}&rdquo;</li>}
          {items.map((i, idx) => (
            <li key={i.id} id={`pi-${idx}`} role="option" aria-selected={idx === active}
              onMouseEnter={() => setActive(idx)} onClick={() => go(i)}
              className={cx("flex cursor-pointer items-center justify-between gap-3 rounded-xl px-3 py-2", idx === active && "bg-accent-soft")}>
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium">{i.label}</span>
                <span className="block truncate text-xs text-muted">{i.hint}</span>
              </span>
              <span className="shrink-0 text-[10px] uppercase tracking-wide text-muted">{i.group}</span>
            </li>
          ))}
          {flagError && <li className="px-3 py-2 text-xs text-muted">Flags could not be loaded; page search still works.</li>}
        </ul>
        <div className="flex items-center gap-3 border-t border-line px-4 py-2 text-[11px] text-muted">
          <Command className="h-3 w-3" aria-hidden /> Arrow keys to move, Enter to open
        </div>
      </div>
    </div>
  );
}

// ---------- scroll helpers ----------

export function ScrollProgress() {
  const [p, setP] = useState(0);
  useEffect(() => {
    const on = () => {
      const h = document.documentElement;
      const max = h.scrollHeight - h.clientHeight;
      setP(max > 0 ? h.scrollTop / max : 0);
    };
    on();
    window.addEventListener("scroll", on, { passive: true });
    window.addEventListener("resize", on);
    return () => {
      window.removeEventListener("scroll", on);
      window.removeEventListener("resize", on);
    };
  }, []);
  return <div aria-hidden className="fixed left-0 top-0 z-[60] h-0.5 bg-accent transition-[width] duration-75" style={{ width: `${p * 100}%` }} />;
}

export function BackToTop() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const on = () => setShow(window.scrollY > 600);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  if (!show) return null;
  return (
    <button type="button" onClick={() => window.scrollTo({ top: 0, behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" })}
      className="fixed bottom-20 right-4 z-40 grid h-10 w-10 place-items-center rounded-full border border-line bg-surface shadow-lift transition hover:border-accent/60"
      aria-label="Back to top">
      <ArrowUp className="h-4 w-4" />
    </button>
  );
}

// Thin highlight line that follows the pointer on desktop; off for touch and reduced motion.
export function CursorLine() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const fine = window.matchMedia("(pointer: fine)").matches;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!fine || reduced) return;
    const el = ref.current;
    if (!el) return;
    el.style.display = "block";
    let raf = 0;
    const on = (e: PointerEvent) => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        el.style.transform = `translateY(${e.clientY}px)`;
      });
    };
    window.addEventListener("pointermove", on, { passive: true });
    return () => {
      window.removeEventListener("pointermove", on);
      cancelAnimationFrame(raf);
    };
  }, []);
  return (
    <div ref={ref} aria-hidden style={{ display: "none" }}
      className="pointer-events-none fixed left-0 top-0 z-0 h-px w-full bg-gradient-to-r from-transparent via-accent/25 to-transparent" />
  );
}

// ---------- footer ----------

export function Footer() {
  const build = buildInfo.builtAt;
  return (
    <footer className="mt-20 border-t border-line">
      <div className="page grid gap-8 py-10 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <p className="flex items-center gap-2 font-semibold"><ShieldCheck className="h-4 w-4 text-accent" aria-hidden /> DeployGuard</p>
          <p className="mt-2 text-sm text-muted">Safe, staged configuration rollouts across independent nodes, with automatic rollback when health guardrails break.</p>
        </div>
        <div>
          <p className="eyebrow">Operate</p>
          <ul className="mt-3 space-y-1.5 text-sm">
            {NAV.filter((n) => ["/dashboard", "/flags", "/rollouts", "/agents"].includes(n.href)).map((n) => (
              <li key={n.href}><Link className="text-muted hover:text-accent" href={n.href}>{n.label}</Link></li>
            ))}
          </ul>
        </div>
        <div>
          <p className="eyebrow">Understand</p>
          <ul className="mt-3 space-y-1.5 text-sm">
            <li><Link className="text-muted hover:text-accent" href="/topology">Topology</Link></li>
            <li><Link className="text-muted hover:text-accent" href="/experiments">Experiments</Link></li>
            <li><Link className="text-muted hover:text-accent" href="/docs">Documentation</Link></li>
            <li><Link className="text-muted hover:text-accent" href="/docs#syllabus">Syllabus and labs</Link></li>
          </ul>
        </div>
        <div>
          <p className="eyebrow">About this build</p>
          <p className="mt-3 text-sm text-muted">Distributed Systems (BCE27PC01), Assignment 6 mini project.</p>
          {build && <p className="mt-2 text-xs text-muted">Dashboard built <time dateTime={build}>{new Date(build).toUTCString()}</time></p>}
        </div>
      </div>
    </footer>
  );
}

// ---------- help and privacy ----------

export function HelpWidget() {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const diag = typeof window === "undefined" ? "" : `page=${pathname}\nuserAgent=${navigator.userAgent}\ntime=${new Date().toISOString()}\nbuild=${buildInfo.builtAt}`;
  return (
    <>
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-controls="help-panel"
        className="fixed bottom-4 right-4 z-50 flex h-11 items-center gap-2 rounded-full bg-accent px-4 text-sm font-medium text-accent-ink shadow-lift transition hover:brightness-110">
        <HelpCircle className="h-4 w-4" aria-hidden /> Help
      </button>
      {open && (
        <div id="help-panel" role="dialog" aria-label="Help and feedback" className="glass fixed bottom-20 right-4 z-50 w-[min(22rem,calc(100vw-2rem))] p-4">
          <div className="mb-2 flex items-center justify-between">
            <p className="flex items-center gap-2 font-semibold"><LifeBuoy className="h-4 w-4 text-accent" aria-hidden /> Help and feedback</p>
            <button className="rounded p-1 text-muted hover:text-ink" onClick={() => setOpen(false)} aria-label="Close help"><X className="h-4 w-4" /></button>
          </div>
          <ul className="space-y-2 text-sm">
            <li><Link className="text-accent hover:underline" href="/docs#troubleshooting">Troubleshooting guide</Link></li>
            <li><Link className="text-accent hover:underline" href="/docs#walkthrough">Demo walkthrough</Link></li>
            <li><span className="text-muted">Press</span> <kbd className="rounded border border-line px-1 text-xs">Ctrl K</kbd> <span className="text-muted">to search.</span></li>
          </ul>
          <p className="mt-3 text-xs text-muted">To report a problem, copy these diagnostics into your course issue tracker or message to the project team. Nothing is sent automatically.</p>
          <div className="mt-2 flex justify-end"><CopyButton text={diag} label="Copy diagnostics" /></div>
        </div>
      )}
    </>
  );
}

export function PrivacyNotice() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    try {
      setShow(localStorage.getItem("dg-privacy-ok") !== "1");
    } catch {
      setShow(true);
    }
  }, []);
  if (!show) return null;
  const dismiss = () => {
    try {
      localStorage.setItem("dg-privacy-ok", "1");
    } catch {
      // If storage is blocked the notice simply returns next visit.
    }
    setShow(false);
  };
  return (
    <div role="region" aria-label="Privacy notice" className="glass fixed bottom-4 left-4 z-40 max-w-sm p-4 text-sm sm:right-auto right-24">
      <p className="font-medium">Privacy</p>
      <p className="mt-1 text-muted">No analytics or third-party trackers. Your theme choice and this notice are remembered in your browser only.</p>
      <button className="btn-ghost mt-3 !py-1 text-xs" onClick={dismiss}>Got it</button>
    </div>
  );
}

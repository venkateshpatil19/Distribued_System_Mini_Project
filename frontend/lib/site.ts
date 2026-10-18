import type { Metadata } from "next";

// Public origin used for canonical URLs, Open Graph and the sitemap. Set NEXT_PUBLIC_SITE_URL in production.
export function siteUrl(): string {
  const raw = process.env.NEXT_PUBLIC_SITE_URL || "http://localhost:3000";
  return raw.replace(/\/+$/, "");
}

export const SITE_NAME = "DeployGuard";
export const SITE_DESCRIPTION =
  "Distributed feature-flag rollouts with health guardrails and automatic rollback, built on independent controllers and agents.";

export type NavItem = { href: string; label: string; description: string; public: boolean; keywords: string[] };

export const NAV: NavItem[] = [
  { href: "/", label: "Overview", description: "What DeployGuard does and how it is built", public: true, keywords: ["home", "architecture"] },
  { href: "/dashboard", label: "Dashboard", description: "Live system health and recent activity", public: false, keywords: ["health", "live", "events"] },
  { href: "/flags", label: "Flags", description: "Feature flags, targeting rules and revisions", public: false, keywords: ["feature", "targeting", "rules", "evaluate"] },
  { href: "/rollouts", label: "Rollouts", description: "Staged rollouts, guardrails and history", public: false, keywords: ["canary", "rollback", "promote", "guardrail"] },
  { href: "/agents", label: "Agents", description: "Node health, heartbeats, versions and fault injection", public: false, keywords: ["nodes", "faults", "heartbeat", "controllers", "traffic"] },
  { href: "/topology", label: "Topology", description: "Controllers, agents and replicated state", public: false, keywords: ["graph", "cluster", "map"] },
  { href: "/experiments", label: "Experiments", description: "Elections, clocks, deadlocks, snapshots and recovery metrics", public: false, keywords: ["lamport", "vector", "bully", "deadlock", "snapshot", "fencing", "cristian"] },
  { href: "/docs", label: "Docs", description: "Architecture, algorithms, setup and troubleshooting", public: true, keywords: ["help", "setup", "syllabus", "labs"] },
];

// Builds per-page metadata with a canonical URL. Operational pages are not indexed.
export function pageMeta(path: string, title: string, description: string, index: boolean): Metadata {
  const url = `${siteUrl()}${path}`;
  return {
    title,
    description,
    alternates: { canonical: url },
    robots: index ? { index: true, follow: true } : { index: false, follow: false },
    openGraph: { title: `${title} | ${SITE_NAME}`, description, url, siteName: SITE_NAME, type: "website" },
  };
}

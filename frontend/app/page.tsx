import Link from "next/link";
import { ArrowRight, BookOpen, Clock3, GitBranch, Lock, Network, Radar, RotateCcw, Split, Workflow } from "lucide-react";
import { LiveStrip } from "./LiveStrip";
import { RevealOnScroll } from "@/components/Reveal";
import { pageMeta } from "@/lib/site";

export const metadata = pageMeta("/", "Safe distributed feature rollouts",
  "DeployGuard stages feature-flag and configuration rollouts across independent agents, watches error-rate and latency guardrails, and rolls back automatically.", true);

const steps = [
  { title: "Version the change", body: "Every flag edit becomes a new global revision, written under a lease with a fencing token.", tone: "border-l-info" },
  { title: "Expose a small slice", body: "A stable hash puts each user in a bucket, so 5% today stays inside 25% tomorrow.", tone: "border-l-accent" },
  { title: "Watch the guardrails", body: "Agents report errors and latency for the users on the new variant. The leader compares them to your limits.", tone: "border-l-warn" },
  { title: "Promote or roll back", body: "Healthy and converged moves to the next stage. A breach restores the previous configuration everywhere.", tone: "border-l-bad" },
];

const mechanisms = [
  { icon: Network, title: "Bully election", body: "Controllers elect the highest live id as leader; only the leader schedules rollouts.", href: "/experiments#elections" },
  { icon: Clock3, title: "Lamport and vector clocks", body: "Every message carries both, so the timeline shows causal order and true concurrency.", href: "/experiments#clocks" },
  { icon: Lock, title: "Leases with fencing tokens", body: "Mutations hold an expiring lease; stale holders are rejected by token, not by trust.", href: "/experiments#leases" },
  { icon: Workflow, title: "Deadlock detection", body: "A global wait-for graph finds cycles and aborts the youngest workflow.", href: "/experiments#deadlock" },
  { icon: Split, title: "Distributed snapshot", body: "Chandy-Lamport markers record agent state and in-flight messages.", href: "/experiments#snapshot" },
  { icon: Radar, title: "Beacon failure detection", body: "Missed heartbeats raise suspicion, never proof, of failure.", href: "/agents" },
];

const faqs = [
  { q: "Are the nodes real or simulated?", a: "Real. Controllers and agents are separate processes that talk only over gRPC. The leader keeps the state and replicates it to the other controllers, each of which saves its copy to a JSON file. What is simulated is the request traffic each agent generates and the faults you inject; both are labelled in the interface." },
  { q: "What happens if the leader crashes mid-rollout?", a: "The other controllers stop receiving its heartbeats, run a Bully election and the new leader continues the rollout from its stored state. Leases and fencing tokens stop the old leader from writing if it comes back with stale assumptions." },
  { q: "How is this rollback different from redeploying the old version?", a: "DeployGuard rollback restores a feature flag's previous configuration across agents in seconds without redeploying anything. A deployment rollback replaces the application build itself, which is slower and affects every feature at once." },
  { q: "Does anything need an account or API key?", a: "No. The demo has no login and no paid services. Because the control plane is unauthenticated, keep it on a private network; the deployment guide explains how." },
];

export default function Home() {
  return (
    <div className="page pb-8 pt-12 sm:pt-16">
      <RevealOnScroll />
      <section className="max-w-3xl">
        <span className="chip border-accent/30 bg-accent-soft text-accent">Distributed Systems · Assignment 6</span>
        <h1 className="mt-5 text-3xl font-semibold leading-tight sm:text-5xl">
          Ship configuration changes <span className="serif-accent">safely</span> across every node
        </h1>
        <p className="mt-5 max-w-2xl text-base text-muted sm:text-lg">
          DeployGuard releases feature flags in stages, watches health on the users who get the change, and rolls back on its own when guardrails break, even while nodes crash, messages arrive late and controllers disagree.
        </p>
        <div className="mt-7 flex flex-wrap gap-3">
          <Link href="/dashboard" className="btn-primary">Open the dashboard <ArrowRight className="h-4 w-4" aria-hidden /></Link>
          <Link href="/docs" className="btn-ghost"><BookOpen className="h-4 w-4" aria-hidden /> Read the docs</Link>
        </div>
      </section>

      <section aria-label="Live cluster status" className="mt-12">
        <LiveStrip />
      </section>

      <section className="reveal mt-20" aria-labelledby="how">
        <h2 id="how" className="text-xl font-semibold"><span aria-hidden className="mr-2">🧭</span>How a rollout <span className="serif-accent">moves</span></h2>
        <ol className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {steps.map((s, i) => (
            <li key={s.title} className={`card card-accent ${s.tone} p-5`}>
              <span className="text-xs font-semibold text-muted">Step {i + 1}</span>
              <p className="mt-1 font-semibold">{s.title}</p>
              <p className="mt-2 text-sm text-muted">{s.body}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="reveal mt-20" aria-labelledby="arch">
        <h2 id="arch" className="text-xl font-semibold"><span aria-hidden className="mr-2">🏗️</span>Architecture</h2>
        <p className="mt-2 max-w-2xl text-sm text-muted">Every box is its own process. Arrows show the protocol each path uses.</p>
        <div className="glass mt-6 overflow-x-auto p-4 sm:p-6">
          <ArchitectureDiagram />
        </div>
      </section>

      <section className="reveal mt-20" aria-labelledby="mech">
        <h2 id="mech" className="text-xl font-semibold"><span aria-hidden className="mr-2">⚙️</span>Mechanisms you can <span className="serif-accent">watch</span></h2>
        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {mechanisms.map((m) => (
            <Link key={m.title} href={m.href} className="card group p-5 transition hover:-translate-y-0.5 hover:shadow-lift">
              <m.icon className="h-5 w-5 text-accent" aria-hidden />
              <p className="mt-3 font-semibold">{m.title}</p>
              <p className="mt-1 text-sm text-muted">{m.body}</p>
              <span className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-accent">Try it <ArrowRight className="h-3.5 w-3.5 transition group-hover:translate-x-0.5" aria-hidden /></span>
            </Link>
          ))}
        </div>
      </section>

      <section className="reveal mt-20 max-w-3xl" aria-labelledby="faq">
        <h2 id="faq" className="text-xl font-semibold"><span aria-hidden className="mr-2">❓</span>Questions</h2>
        <div className="mt-6 divide-y divide-line rounded-2xl border border-line bg-surface">
          {faqs.map((f) => (
            <details key={f.q} className="group p-5 [&_summary::-webkit-details-marker]:hidden">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 font-medium">
                {f.q}
                <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full border border-line text-muted transition group-open:rotate-45" aria-hidden>+</span>
              </summary>
              <p className="mt-3 text-sm text-muted">{f.a}</p>
            </details>
          ))}
        </div>
      </section>

      <section className="reveal mt-20">
        <div className="card flex flex-col items-start gap-4 p-6 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-semibold">See a bad canary get caught</p>
            <p className="text-sm text-muted">Run the replayable experiment and watch the rollback converge across agents.</p>
          </div>
          <Link href="/experiments#scenarios" className="btn-primary"><RotateCcw className="h-4 w-4" aria-hidden /> Run the experiment</Link>
        </div>
      </section>
      <p className="mt-6 flex items-center gap-2 text-xs text-muted"><GitBranch className="h-3.5 w-3.5" aria-hidden /> Source layout and commands are in the repository README.</p>
    </div>
  );
}

function ArchitectureDiagram() {
  const box = "fill-surface stroke-line";
  return (
    <svg viewBox="0 0 860 360" className="h-auto w-full min-w-[640px]" role="img" aria-label="Dashboard talks to controllers over HTTP and SSE. Three controllers elect a leader over gRPC and the leader replicates its state to the others, which save it to JSON files. Agents register, heartbeat, pull configuration and report metrics to the leader over gRPC.">
      <defs>
        <marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" className="fill-muted" />
        </marker>
      </defs>
      <g className="text-[12px]">
        <rect x="20" y="150" width="130" height="60" rx="12" className={box} />
        <text x="85" y="177" textAnchor="middle" className="fill-ink font-semibold">Dashboard</text>
        <text x="85" y="195" textAnchor="middle" className="fill-muted text-[10px]">Next.js</text>

        {[0, 1, 2].map((i) => (
          <g key={i}>
            <rect x="250" y={40 + i * 100} width="150" height="60" rx="12" className={i === 2 ? "fill-accent-soft stroke-accent" : box} />
            <text x="325" y={67 + i * 100} textAnchor="middle" className="fill-ink font-semibold">controller-{i + 1}</text>
            <text x="325" y={85 + i * 100} textAnchor="middle" className="fill-muted text-[10px]">{i === 2 ? "leader (highest id)" : "follower"}</text>
          </g>
        ))}
        <path d="M410 70 Q440 120 410 170 M410 170 Q440 220 410 270" className="fill-none stroke-accent" strokeDasharray="4 4" />
        <text x="448" y="125" className="fill-accent text-[10px]">Bully election</text>
        <text x="448" y="139" className="fill-accent text-[10px]">state replication</text>

        <rect x="520" y="40" width="140" height="60" rx="12" className={box} />
        <text x="590" y="67" textAnchor="middle" className="fill-ink font-semibold">JSON state files</text>
        <text x="590" y="85" textAnchor="middle" className="fill-muted text-[10px]">one copy per controller</text>

        {[0, 1, 2].map((i) => (
          <g key={i}>
            <rect x="740" y={60 + i * 90} width="100" height="54" rx="12" className={box} />
            <text x="790" y={84 + i * 90} textAnchor="middle" className="fill-ink font-semibold">agent</text>
            <text x="790" y={101 + i * 90} textAnchor="middle" className="fill-muted text-[10px]">traffic + flags</text>
          </g>
        ))}

        <line x1="150" y1="180" x2="248" y2="170" className="stroke-muted" markerEnd="url(#arr)" />
        <text x="160" y="160" className="fill-muted text-[10px]">HTTP + SSE</text>
        <line x1="400" y1="60" x2="518" y2="66" className="stroke-muted" markerEnd="url(#arr)" />
        <text x="430" y="52" className="fill-muted text-[10px]">save</text>
        <line x1="738" y1="110" x2="402" y2="168" className="stroke-info" strokeDasharray="5 3" markerEnd="url(#arr)" />
        <line x1="738" y1="290" x2="402" y2="278" className="stroke-info" strokeDasharray="5 3" markerEnd="url(#arr)" />
        <text x="560" y="150" className="fill-info text-[10px]">gRPC register, heartbeat, pull</text>
        <text x="540" y="300" className="fill-info text-[10px]">gRPC acks, metrics (to leader)</text>
      </g>
    </svg>
  );
}

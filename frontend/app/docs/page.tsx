import Link from "next/link";
import { CodeLine } from "@/components/ui";
import { RevealOnScroll } from "@/components/Reveal";
import { pageMeta } from "@/lib/site";

export const metadata = pageMeta("/docs", "Documentation",
  "How DeployGuard is built: architecture, distributed algorithms, one-click local setup, troubleshooting and syllabus mapping.", true);

const toc = [
  ["architecture", "Architecture"], ["algorithms", "Algorithms"], ["setup", "Setup"], ["walkthrough", "Demo walkthrough"],
  ["troubleshooting", "Troubleshooting"], ["security", "Security"], ["syllabus", "Syllabus and labs"],
] as const;

const algorithms: Array<[string, string, string, string]> = [
  ["Bully leader election", "core/BullyElection.java, controller/PeerClient.java", "Production-like", "Controllers exchange ELECTION, OK, COORDINATOR and heartbeat messages over gRPC. Epochs order successive leaders."],
  ["Lamport clocks", "common/LamportClock.java, NodeClock.java", "Production-like", "Every gRPC message, agent report and stored event carries a Lamport time."],
  ["Vector clocks", "common/VectorClock.java", "Production-like", "Stored with every event; the API compares two events as before, after or concurrent."],
  ["Cristian's algorithm", "agent/AgentMain.java (syncClock)", "Teaching demonstration", "Agents run with a simulated skew and estimate their offset from a controller every 10 s."],
  ["Leases and fencing tokens", "controller/Leases.java", "Production-like", "Every flag or rollout mutation holds an expiring lease and presents its token inside the same transaction."],
  ["Wait-for-graph deadlock detection", "core/WaitForGraph.java, controller/DeadlockDemo.java", "Teaching demonstration", "Centralized detection on the leader over the recorded wait edges; youngest workflow is aborted."],
  ["Chandy-Lamport snapshot", "controller/Snapshots.java, agent marker handling", "Teaching demonstration", "Markers travel on each agent's ordered command queue and come back on its ordered report channel."],
  ["Beacon failure detection", "core/FailureDetector.java", "Production-like", "Heartbeat age classifies agents as delayed, suspected or disconnected, using the leader's clock only, so agent clock skew does not matter."],
  ["Retries, idempotency, recovery", "common/Retry.java, agent report outbox, StateStore.ack", "Production-like", "Backoff with jitter, reports resent until accepted, duplicate events dropped by id, acks that only move forward, pull-based catch-up."],
  ["Primary-backup replication", "controller/Replicator.java, StateStore.java", "Production-like", "The leader pushes its state to followers every second; copies from an older epoch are refused, and a leader without a majority stops serving."],
  ["Lodha-Kshemkalyani fair mutual exclusion", "labs/lab-fair-mutex", "Teaching demonstration", "Simulation with message counts compared against Ricart-Agrawala."],
  ["Knapp's deadlock classification", "docs/ALGORITHMS.md", "Conceptual", "Path-pushing, edge-chasing, diffusing computation and global-state detection; edge-chasing is simulated in lab 3."],
];

const troubleshooting: Array<[string, string]> = [
  ["The dashboard says the control plane is unreachable", "The Next.js server proxies /api to CONTROLLER_URL. Check that at least one controller is running (see the logs folder) and that CONTROLLER_URL lists the controller HTTP ports."],
  ["Agents stay SUSPECTED after a partition", "Partitions expire on their own. If an agent never returns, look at logs/agent-N.log. The start script restarts a crashed agent, which registers with a new incarnation."],
  ["No leader is shown", "Controllers need their PEERS list to reach each other on gRPC ports 9091-9093, and a leader needs a majority (2 of 3) to serve. Start at least two controllers."],
  ["A rollout never promotes", "Promotion needs the observation window to pass, at least minRequests on the new variant, and every live agent in that environment to have applied the stage revision. The rollout card shows which one is missing."],
  ["Edits fail with a 409 conflict", "Another operation holds the lease for that flag and environment, or a rollout owns it. Wait a second and retry, or abort the rollout first."],
];

export default function DocsPage() {
  return (
    <div className="page py-10">
      <RevealOnScroll />
      <header className="max-w-3xl">
        <p className="eyebrow">Documentation</p>
        <h1 className="mt-1 text-2xl font-semibold sm:text-3xl">How DeployGuard <span className="serif-accent">works</span></h1>
        <p className="mt-2 text-muted">The repository&apos;s <code className="font-mono">docs/</code> folder holds the full versions: ARCHITECTURE, ALGORITHMS, DEPLOYMENT, RUNBOOK, TEST_RESULTS and SYLLABUS_MAPPING.</p>
      </header>
      <div className="mt-8 grid gap-10 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label="On this page" className="lg:sticky lg:top-24 lg:self-start">
          <ul className="flex flex-wrap gap-2 text-sm lg:flex-col lg:gap-1">
            {toc.map(([id, label]) => <li key={id}><a href={`#${id}`} className="block rounded-lg px-3 py-1.5 text-muted hover:bg-surface-2 hover:text-ink">{label}</a></li>)}
          </ul>
        </nav>
        <article className="min-w-0 space-y-14 text-sm leading-7">
          <section id="architecture" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🏗️</span>Architecture</h2>
            <ul className="mt-3 list-disc space-y-2 pl-5 text-muted">
              <li><strong className="text-ink">Controllers</strong> (3 processes) serve the HTTP API and SSE stream, run gRPC for agents and peers, and elect a leader with the Bully algorithm. Only the leader runs the rollout scheduler and the failure monitor.</li>
              <li><strong className="text-ink">Agents</strong> register and heartbeat with the leader over gRPC, receive commands in the heartbeat replies, pull new configuration revisions, acknowledge them, generate simulated traffic and report metrics.</li>
              <li><strong className="text-ink">State</strong> (flags, global revisions, rollouts, fencing guards, events) lives in the leader&apos;s memory and is saved to <code className="font-mono">data/controller-N.json</code>. The leader replicates it to the followers over gRPC, so a new leader continues from the same data.</li>
              <li><strong className="text-ink">Dashboard</strong> (Next.js) proxies <code className="font-mono">/api</code> to any reachable controller; followers forward the request to the leader.</li>
            </ul>
            <p className="mt-3 text-muted">Revisions are global and totally ordered. An agent applies a bundle only if its revision is newer than the one it holds, which makes delayed, duplicated and reordered messages harmless. Heartbeat replies carry the desired revision and the agent pulls it over gRPC when it is behind.</p>
          </section>

          <section id="algorithms" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🧠</span>Algorithms and their status</h2>
            <div className="table-wrap mt-4">
              <table className="table">
                <thead><tr><th>Mechanism</th><th>Status</th><th>Where</th><th>Notes</th></tr></thead>
                <tbody>
                  {algorithms.map(([m, where, status, note]) => (
                    <tr key={m}><td className="font-medium">{m}</td><td className="whitespace-nowrap">{status}</td><td className="font-mono text-xs">{where}</td><td className="text-muted">{note}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mt-3 text-muted">Production-like means it protects the real rollout workflow. Teaching demonstration means it runs on the live system but exists to illustrate the concept. Conceptual means documented, not implemented in the product.</p>
          </section>

          <section id="setup" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🛠️</span>Setup</h2>
            <p className="mt-2 text-muted">Needs Java 21 and Node 20 (Maven only for the first build). On Windows double-click:</p>
            <div className="mt-2 space-y-2">
              <CodeLine code="start.bat" />
              <CodeLine code="stop.bat" />
            </div>
            <p className="mt-4 text-muted">On Linux, macOS or Git Bash:</p>
            <div className="mt-2 space-y-2">
              <CodeLine code="(cd backend && mvn -DskipTests package) && (cd frontend && npm ci && npm run build)" />
              <CodeLine code="scripts/run-local.sh" />
              <CodeLine code="scripts/stop-local.sh" />
            </div>
            <p className="mt-3 text-muted">This starts 3 controllers, 3 agents and the dashboard on http://localhost:3000. Logs go to <code className="font-mono">logs/</code> and state to <code className="font-mono">data/</code>; delete <code className="font-mono">data/</code> to start fresh.</p>
          </section>

          <section id="walkthrough" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🎬</span>Demo walkthrough</h2>
            <ol className="mt-3 list-decimal space-y-2 pl-5 text-muted">
              <li>Open the <Link className="text-accent underline" href="/dashboard">dashboard</Link>: note the leader, live agents and traffic.</li>
              <li>On <Link className="text-accent underline" href="/rollouts">Rollouts</Link>, start a rollout of <code className="font-mono">new-checkout</code> with stages 5, 25, 50, 100.</li>
              <li>On <Link className="text-accent underline" href="/agents#faults">Agents</Link>, inject an unhealthy release for <code className="font-mono">new-checkout</code>. Within one observation window the leader rolls back and every agent converges on the rollback revision.</li>
              <li>Isolate or crash the leader from the Controllers table. Watch the election in the event feed and the topology view.</li>
              <li>On <Link className="text-accent underline" href="/experiments">Experiments</Link>, run the lease race, a deadlock scenario and a snapshot, then replay a scenario and compare results.</li>
            </ol>
          </section>

          <section id="troubleshooting" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🩺</span>Troubleshooting</h2>
            <div className="mt-3 divide-y divide-line rounded-2xl border border-line bg-surface">
              {troubleshooting.map(([q, a]) => (
                <details key={q} className="group p-4 [&_summary::-webkit-details-marker]:hidden">
                  <summary className="flex cursor-pointer list-none items-center justify-between gap-4 font-medium">{q}<span className="text-muted transition group-open:rotate-45" aria-hidden>+</span></summary>
                  <p className="mt-2 text-muted">{a}</p>
                </details>
              ))}
            </div>
          </section>

          <section id="security" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">🛡️</span>Security</h2>
            <p className="mt-2 text-muted">By design this demo has no login. Anyone who can reach the dashboard or a controller can change flags, inject faults and crash processes. Keep it on localhost, a private cluster network or behind a VPN. If you publish it on a custom domain, put it behind an authenticating reverse proxy first; see the custom-domain section of <code className="font-mono">docs/DEPLOYMENT.md</code>.</p>
          </section>

          <section id="syllabus" className="reveal scroll-mt-24">
            <h2 className="text-xl font-semibold"><span aria-hidden className="mr-2">📚</span>Syllabus and labs</h2>
            <p className="mt-2 text-muted">Every topic of Units I to IV, the self-study topics and the six lab assignments are mapped to code, a runnable demo, a test or a case study in <code className="font-mono">docs/SYLLABUS_MAPPING.md</code>. Companion exercises live under <code className="font-mono">labs/</code> and are kept separate from the product.</p>
            <ul className="mt-3 grid gap-2 sm:grid-cols-2">
              {[
                ["Lab 1", "Client-server over TCP sockets (and gRPC in the product)"],
                ["Lab 2", "Bully and Ring election across separate processes"],
                ["Lab 3", "Wait-for graph and Chandy-Misra-Haas probes"],
                ["Lab 4", "Lamport, vector clocks and Berkeley synchronization"],
                ["Lab 5", "MapReduce word count across worker processes"],
                ["Lab 6", "DeployGuard as a multi-process distributed application"],
              ].map(([k, v]) => <li key={k} className="card p-3"><span className="font-semibold">{k}</span><span className="block text-muted">{v}</span></li>)}
            </ul>
          </section>
        </article>
      </div>
    </div>
  );
}

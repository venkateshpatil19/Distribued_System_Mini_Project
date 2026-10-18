import { pageMeta } from "@/lib/site";
import { AgentsClient } from "./AgentsClient";

export const metadata = pageMeta("/agents", "Agents and faults", "Agent and controller health from heartbeats, applied configuration versions, clock offsets and fault injection controls.", false);

export default function Page() {
  return <AgentsClient />;
}

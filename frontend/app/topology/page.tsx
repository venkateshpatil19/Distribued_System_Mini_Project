import { pageMeta } from "@/lib/site";
import { TopologyClient } from "./TopologyClient";

export const metadata = pageMeta("/topology", "Topology", "Interactive map of DeployGuard controllers, agents, and their replicated state files, with live status.", false);

export default function Page() {
  return <TopologyClient />;
}

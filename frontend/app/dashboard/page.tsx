import { pageMeta } from "@/lib/site";
import { DashboardClient } from "./DashboardClient";

export const metadata = pageMeta("/dashboard", "Dashboard", "Live system health, traffic, active rollouts and recent activity across the DeployGuard cluster.", false);

export default function Page() {
  return <DashboardClient />;
}

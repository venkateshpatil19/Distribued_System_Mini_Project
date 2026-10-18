import { pageMeta } from "@/lib/site";
import { ExperimentsClient } from "./ExperimentsClient";

export const metadata = pageMeta("/experiments", "Experiments", "Replayable fault experiments plus live demonstrations of Bully election, Lamport and vector clocks, Cristian's algorithm, fencing tokens, deadlock detection and Chandy-Lamport snapshots.", false);

export default function Page() {
  return <ExperimentsClient />;
}

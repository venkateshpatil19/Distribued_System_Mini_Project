import { Suspense } from "react";
import { pageMeta } from "@/lib/site";
import { LoadingBlock } from "@/components/ui";
import { RolloutsClient } from "./RolloutsClient";

export const metadata = pageMeta("/rollouts", "Rollouts", "Launch staged rollouts with health guardrails, then promote, pause, resume, abort or roll back with a full audit history.", false);

export default function Page() {
  return (
    <Suspense fallback={<div className="page py-10"><LoadingBlock label="Loading rollouts" /></div>}>
      <RolloutsClient />
    </Suspense>
  );
}

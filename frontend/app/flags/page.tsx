import { Suspense } from "react";
import { pageMeta } from "@/lib/site";
import { LoadingBlock } from "@/components/ui";
import { FlagsClient } from "./FlagsClient";

export const metadata = pageMeta("/flags", "Flags", "Search feature flags, edit targeting rules per environment, preview exposure and inspect revision history.", false);

export default function Page() {
  return (
    <Suspense fallback={<div className="page py-10"><LoadingBlock label="Loading flags" /></div>}>
      <FlagsClient />
    </Suspense>
  );
}

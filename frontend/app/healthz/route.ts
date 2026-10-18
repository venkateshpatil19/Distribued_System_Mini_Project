import buildInfo from "@/lib/build-info.json";

export const dynamic = "force-dynamic";

// Liveness probe for the dashboard container; it does not depend on the control plane.
export function GET() {
  return Response.json({ status: "ok", service: "frontend", buildTime: buildInfo.builtAt });
}

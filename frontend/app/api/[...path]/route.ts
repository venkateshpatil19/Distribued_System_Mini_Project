import type { NextRequest } from "next/server";

// Proxies /api/* to a controller. CONTROLLER_URL may list several controllers separated by commas;
// if one is down the next is tried, so the dashboard keeps working while a controller restarts.
export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const UPSTREAMS = (process.env.CONTROLLER_URL || "http://localhost:8081")
  .split(",")
  .map((u) => u.trim().replace(/\/+$/, ""))
  .filter(Boolean);
const PASS_HEADERS = ["content-type", "x-actor", "accept", "last-event-id"];

// Index of the controller that answered last; we start there next time.
let preferred = 0;

async function proxy(req: NextRequest, ctx: { params: { path: string[] } }) {
  const path = ctx.params.path.map(encodeURIComponent).join("/");
  const headers = new Headers();
  for (const h of PASS_HEADERS) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  const body = ["GET", "HEAD"].includes(req.method) ? undefined : await req.text();
  const errors: string[] = [];

  for (let n = 0; n < UPSTREAMS.length; n++) {
    const i = (preferred + n) % UPSTREAMS.length;
    const url = `${UPSTREAMS[i]}/api/${path}${req.nextUrl.search}`;
    try {
      const upstream = await fetch(url, { method: req.method, headers, body, cache: "no-store", signal: req.signal });
      // 503 means this controller cannot reach a leader (for example it is partitioned): try the next one.
      if (upstream.status === 503 && n < UPSTREAMS.length - 1) {
        errors.push(`${UPSTREAMS[i]}: 503`);
        await upstream.body?.cancel();
        continue;
      }
      preferred = i;
      const out = new Headers();
      for (const h of ["content-type", "cache-control", "x-served-by"]) {
        const v = upstream.headers.get(h);
        if (v) out.set(h, v);
      }
      if (path === "stream") {
        out.set("Cache-Control", "no-cache, no-transform");
        out.set("X-Accel-Buffering", "no");
      }
      return new Response(upstream.body, { status: upstream.status, headers: out });
    } catch (e) {
      if (req.signal.aborted) return new Response(null, { status: 499 });
      // Connection refused or reset: this controller is down, try the next one.
      errors.push(`${UPSTREAMS[i]}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  return Response.json({ error: `No controller reachable (${errors.join("; ")})` }, { status: 502 });
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };

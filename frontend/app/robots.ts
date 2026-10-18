import type { MetadataRoute } from "next";
import { siteUrl } from "@/lib/site";

// Operational pages and the API are excluded: they show live, unauthenticated control-plane state.
export default function robots(): MetadataRoute.Robots {
  return {
    rules: [{ userAgent: "*", allow: ["/", "/docs"], disallow: ["/api/", "/dashboard", "/flags", "/rollouts", "/agents", "/topology", "/experiments"] }],
    sitemap: `${siteUrl()}/sitemap.xml`,
    host: siteUrl(),
  };
}

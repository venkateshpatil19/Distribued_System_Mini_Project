import type { MetadataRoute } from "next";
import { NAV, siteUrl } from "@/lib/site";
import buildInfo from "@/lib/build-info.json";

export default function sitemap(): MetadataRoute.Sitemap {
  const built = new Date(buildInfo.builtAt);
  return NAV.filter((n) => n.public).map((n) => ({
    url: `${siteUrl()}${n.href === "/" ? "/" : n.href}`,
    lastModified: built,
    changeFrequency: "weekly",
    priority: n.href === "/" ? 1 : 0.7,
  }));
}

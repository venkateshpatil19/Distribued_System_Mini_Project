import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "DeployGuard",
    short_name: "DeployGuard",
    description: "Distributed feature-flag rollouts with automatic rollback.",
    start_url: "/dashboard",
    display: "standalone",
    background_color: "#f7f8f6",
    theme_color: "#157a48",
    icons: [{ src: "/icon.svg", sizes: "any", type: "image/svg+xml" }, { src: "/apple-icon.png", sizes: "180x180", type: "image/png" }],
  };
}

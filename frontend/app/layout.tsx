import "@fontsource-variable/inter";
import "@fontsource/instrument-serif/400.css";
import "@fontsource/instrument-serif/400-italic.css";
import "./globals.css";
import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { BackToTop, CommandPalette, CursorLine, Footer, Header, HelpWidget, PrivacyNotice, ScrollProgress, THEME_SCRIPT } from "@/components/chrome";
import { ToastProvider } from "@/components/ui";
import { SITE_DESCRIPTION, SITE_NAME, siteUrl } from "@/lib/site";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl()),
  title: { default: `${SITE_NAME}: safe distributed feature rollouts`, template: `%s | ${SITE_NAME}` },
  description: SITE_DESCRIPTION,
  applicationName: SITE_NAME,
  alternates: { canonical: siteUrl() + "/" },
  openGraph: { type: "website", siteName: SITE_NAME, title: SITE_NAME, description: SITE_DESCRIPTION, url: siteUrl() + "/", images: [{ url: "/og.png", width: 1200, height: 630, alt: "DeployGuard" }] },
  twitter: { card: "summary_large_image", title: SITE_NAME, description: SITE_DESCRIPTION, images: ["/og.png"] },
  icons: { icon: [{ url: "/icon.svg", type: "image/svg+xml" }], apple: "/apple-icon.png" },
  manifest: "/manifest.webmanifest",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f7f8f6" },
    { media: "(prefers-color-scheme: dark)", color: "#0b0f0d" },
  ],
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_SCRIPT }} />
      </head>
      <body className="min-h-screen overflow-x-hidden">
        <a href="#content" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded-xl focus:bg-accent focus:px-4 focus:py-2 focus:text-accent-ink">
          Skip to content
        </a>
        <ToastProvider>
          <ScrollProgress />
          <CursorLine />
          <Header />
          <main id="content" tabIndex={-1} className="relative z-10 outline-none">{children}</main>
          <Footer />
          <CommandPalette />
          <HelpWidget />
          <BackToTop />
          <PrivacyNotice />
        </ToastProvider>
      </body>
    </html>
  );
}

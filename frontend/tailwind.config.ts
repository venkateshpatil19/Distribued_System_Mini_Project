import type { Config } from "tailwindcss";

const v = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  darkMode: ["class", '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        bg: v("bg"),
        surface: v("surface"),
        "surface-2": v("surface-2"),
        line: v("line"),
        ink: v("ink"),
        muted: v("muted"),
        accent: v("accent"),
        "accent-ink": v("accent-ink"),
        "accent-soft": v("accent-soft"),
        ok: v("ok"),
        warn: v("warn"),
        bad: v("bad"),
        info: v("info"),
      },
      fontFamily: {
        sans: ['"Inter Variable"', "Inter", "system-ui", "sans-serif"],
        serif: ['"Instrument Serif"', "Georgia", "serif"],
        mono: ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      boxShadow: {
        card: "0 1px 2px rgb(0 0 0 / 0.04), 0 4px 16px -4px rgb(var(--shadow) / 0.10)",
        lift: "0 2px 4px rgb(0 0 0 / 0.05), 0 12px 32px -8px rgb(var(--shadow) / 0.18)",
      },
      borderRadius: { xl: "14px", "2xl": "18px" },
      maxWidth: { page: "1200px" },
    },
  },
  plugins: [],
};
export default config;

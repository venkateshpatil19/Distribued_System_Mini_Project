import { describe, expect, it } from "vitest";
import { compact, compareVc, ms, pct, relativeTime, showValue, stageLabel, titleCase } from "@/lib/format";
import { describe as describeEvent, eventTone } from "@/lib/events";
import { NAV, pageMeta } from "@/lib/site";
import { streamToEvent } from "@/lib/api";

describe("format helpers", () => {
  it("formats relative time", () => {
    const now = Date.parse("2026-01-01T00:10:00Z");
    expect(relativeTime("2026-01-01T00:09:58Z", now)).toBe("just now");
    expect(relativeTime("2026-01-01T00:09:30Z", now)).toBe("30s ago");
    expect(relativeTime("2026-01-01T00:00:00Z", now)).toBe("10 min ago");
    expect(relativeTime(null, now)).toBe("never");
    expect(relativeTime("garbage", now)).toBe("unknown");
  });
  it("formats durations, percentages and counts", () => {
    expect(ms(250)).toBe("250 ms");
    expect(ms(2500)).toBe("2.50 s");
    expect(ms(null)).toBe("n/a");
    expect(pct(0.0525)).toBe("5.3%");
    expect(compact(12_500)).toBe("13k");
    expect(compact(1500)).toBe("1.5k");
  });
  it("shows JSON flag values", () => {
    expect(showValue('"blue"')).toBe('"blue"');
    expect(showValue("true")).toBe("true");
    expect(showValue('{"a":1}')).toBe('{"a":1}');
    expect(showValue("not json")).toBe("not json");
  });
  it("labels stages and titles", () => {
    expect(stageLabel([5, 25, 100], 1)).toBe("stage 2 of 3 (25%)");
    expect(titleCase("ROLLED_BACK")).toBe("Rolled Back");
  });
});

describe("vector clock comparison matches the backend rule", () => {
  it("detects order and concurrency", () => {
    expect(compareVc({ a: 1 }, { a: 1, b: 1 })).toBe("BEFORE");
    expect(compareVc({ a: 2, b: 1 }, { a: 1, b: 1 })).toBe("AFTER");
    expect(compareVc({ a: 1 }, { b: 1 })).toBe("CONCURRENT");
    expect(compareVc({}, {})).toBe("EQUAL");
  });
});

describe("events and metadata", () => {
  it("describes events and assigns tones", () => {
    const e = streamToEvent({ type: "ROLLOUT_AUTO_ROLLBACK", eventId: "x", node: "controller-3", lamport: 9, vclock: {}, wallTs: 0, body: { rolloutId: 4, detail: "error rate high", outcome: "breach" } });
    expect(describeEvent(e)).toContain("rolled back automatically");
    expect(eventTone(e)).toBe("bad");
    const sim = streamToEvent({ type: "FAULT_INJECTED", eventId: "y", node: "c", lamport: 1, vclock: {}, wallTs: 0, body: { simulated: true, outcome: "injected", target: "agent-1", type: "partition" } });
    expect(eventTone(sim)).toBe("info");
  });
  it("builds canonical, non-indexed metadata for operational pages", () => {
    const m = pageMeta("/agents", "Agents", "desc", false);
    expect(m.alternates?.canonical).toMatch(/\/agents$/);
    expect(m.robots).toEqual({ index: false, follow: false });
    expect(NAV.filter((n) => n.public).map((n) => n.href)).toEqual(["/", "/docs"]);
  });
});

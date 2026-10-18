"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { streamToEvent, type DgEvent, type StreamMsg } from "./api";

export type PollState<T> = {
  data: T | null;
  error: string | null;
  loading: boolean;
  refreshedAt: number | null;
  refresh: () => void;
};

// Polls an async loader. Keeps the last good data visible while a refresh fails, and pauses
// while the tab is hidden so idle dashboards do not hammer the control plane.
export function usePoll<T>(load: () => Promise<T>, intervalMs = 3000, deps: unknown[] = []): PollState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshedAt, setRefreshedAt] = useState<number | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;
  const [tick, setTick] = useState(0);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      if (typeof document !== "undefined" && document.hidden) {
        timer = setTimeout(run, intervalMs);
        return;
      }
      try {
        const d = await loadRef.current();
        if (cancelled) return;
        setData(d);
        setError(null);
        setRefreshedAt(Date.now());
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) {
          setLoading(false);
          if (intervalMs > 0) timer = setTimeout(run, intervalMs);
        }
      }
    };
    run();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, tick, ...deps]);

  return { data, error, loading, refreshedAt, refresh };
}

export type StreamStatus = "connecting" | "live" | "reconnecting" | "offline";

// Subscribes to the controller's Server-Sent Events stream. EventSource reconnects on its own;
// we only track status and keep a bounded list of recent events.
export function useEventStream(max = 60): { events: DgEvent[]; status: StreamStatus } {
  const [events, setEvents] = useState<DgEvent[]>([]);
  const [status, setStatus] = useState<StreamStatus>("connecting");

  useEffect(() => {
    if (typeof window === "undefined" || typeof EventSource === "undefined") return;
    const es = new EventSource("/api/stream");
    es.onopen = () => setStatus("live");
    es.onerror = () => setStatus(navigator.onLine ? "reconnecting" : "offline");
    es.addEventListener("event", (e) => {
      try {
        const msg = JSON.parse((e as MessageEvent).data) as StreamMsg;
        const ev = streamToEvent(msg);
        setEvents((prev) => (prev.some((p) => p.eventId === ev.eventId) ? prev : [ev, ...prev].slice(0, max)));
      } catch {
        // Ignore malformed frames; the next one will arrive shortly.
      }
    });
    return () => es.close();
  }, [max]);

  return { events, status };
}

export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  return online;
}

// Adds .is-visible to .reveal elements as they scroll into view.
export function useReveal() {
  useEffect(() => {
    const els = Array.from(document.querySelectorAll<HTMLElement>(".reveal:not(.is-visible)"));
    if (!("IntersectionObserver" in window)) {
      els.forEach((el) => el.classList.add("is-visible"));
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) {
            e.target.classList.add("is-visible");
            io.unobserve(e.target);
          }
        }
      },
      { rootMargin: "0px 0px -40px 0px" },
    );
    els.forEach((el) => io.observe(el));
    return () => io.disconnect();
  });
}

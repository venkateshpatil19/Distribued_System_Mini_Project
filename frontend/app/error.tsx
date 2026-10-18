"use client";

import { useEffect } from "react";

// Route-level error boundary: shows the failure honestly and offers a retry.
export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error(error);
  }, [error]);
  return (
    <div className="page py-24 text-center">
      <p className="eyebrow">Something broke</p>
      <h1 className="mt-2 text-2xl font-semibold">This view failed to render</h1>
      <p className="mx-auto mt-3 max-w-md text-muted">{error.message || "An unexpected error occurred."}</p>
      <button className="btn-primary mt-6" onClick={reset}>Try again</button>
    </div>
  );
}

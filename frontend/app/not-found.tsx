import Link from "next/link";

export default function NotFound() {
  return (
    <div className="page py-24 text-center">
      <p className="eyebrow">404</p>
      <h1 className="mt-2 text-2xl font-semibold">This page <span className="serif-accent">doesn&apos;t exist</span></h1>
      <p className="mt-3 text-muted">Check the address, or use search (Ctrl K) to find a page or flag.</p>
      <div className="mt-6 flex justify-center gap-3">
        <Link href="/" className="btn-primary">Go to overview</Link>
        <Link href="/dashboard" className="btn-ghost">Open dashboard</Link>
      </div>
    </div>
  );
}

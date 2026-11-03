"use client";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <section className="card">
      <h1>Something went wrong</h1>
      <p className="muted">The page couldn&apos;t load. Your data is unaffected: nothing is changed by viewing a page.</p>
      {error.digest && <p className="muted mono">Reference: {error.digest}</p>}
      <button type="button" onClick={reset}>Try again</button>
    </section>
  );
}

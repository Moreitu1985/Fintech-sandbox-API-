"use client";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <section className="card">
      <h1>Something went wrong</h1>
      <p className="muted">
        This page hit an unexpected problem. If you had just submitted a form, check whether the change was
        made (for a transfer, look at the account&apos;s statement) before trying again: transfers retried from the
        same form can&apos;t pay twice.
      </p>
      {error.digest && <p className="muted mono">Reference: {error.digest}</p>}
      <button type="button" onClick={reset}>Try again</button>
    </section>
  );
}

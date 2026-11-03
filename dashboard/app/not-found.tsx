import Link from "next/link";

export default function NotFound() {
  return (
    <main className="login">
      <div className="card">
        <h1>Not found</h1>
        <p className="muted">It doesn&apos;t exist, or it belongs to another tenant.</p>
        <Link href="/">Back to the overview</Link>
      </div>
    </main>
  );
}

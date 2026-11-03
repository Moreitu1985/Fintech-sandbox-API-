import Link from "next/link";
import { load, requireClient } from "@/lib/auth.ts";
import { when } from "@/lib/format.ts";
import { NewEndpointForm } from "./forms.tsx";

export const metadata = { title: "Webhooks" };

export default async function WebhooksPage({ searchParams }: { searchParams: Promise<{ notice?: string }> }) {
  const lf = await requireClient();
  const endpoints = await load(() => lf.webhooks.list());
  const { notice } = await searchParams;
  return (
    <>
      <div className="page-head">
        <div>
          <h1>Webhooks</h1>
          <p>LedgerForge calls your server when money moves. Each delivery is signed (Standard Webhooks) and retried for about a day.</p>
        </div>
      </div>
      {notice && <div className="alert success" role="status">{notice}</div>}
      <section className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>URL</th><th>Events</th><th>Status</th><th>Created</th></tr></thead>
            <tbody>
              {endpoints.map((e) => (
                <tr key={e.id}>
                  <td><Link href={`/webhooks/${e.id}`} className="mono">{e.url}</Link>{e.description && <div className="muted">{e.description}</div>}</td>
                  <td>{e.eventTypes.join(", ")}</td>
                  <td>{e.enabled ? <span className="badge ok">Active</span> : <span className="badge bad">Disabled</span>}</td>
                  <td className="muted">{when(e.createdAt)}</td>
                </tr>
              ))}
              {endpoints.length === 0 && <tr><td colSpan={4} className="muted">No endpoints yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card">
        <h2>Register an endpoint</h2>
        <NewEndpointForm />
      </section>
    </>
  );
}

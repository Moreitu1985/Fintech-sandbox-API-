import Link from "next/link";
import { load, pageParam, requireClient, requireId } from "@/lib/auth.ts";
import { notice as noticeFor } from "@/lib/notices.ts";
import { shortId, when } from "@/lib/format.ts";
import { endpointAction } from "../actions.ts";
import { RotateSecretForm } from "../forms.tsx";

export const metadata = { title: "Webhook endpoint" };

const STATUS_BADGE: Record<string, string> = { SUCCEEDED: "ok", FAILED: "bad", CANCELLED: "", PENDING: "warn" };

function Op({ id, op, label, kind = "secondary", deliveryId }: {
  id: string; op: string; label: string; kind?: string; deliveryId?: string;
}) {
  return (
    <form action={endpointAction} className="inline">
      <input type="hidden" name="endpointId" value={id} />
      <input type="hidden" name="op" value={op} />
      {deliveryId && <input type="hidden" name="deliveryId" value={deliveryId} />}
      <button type="submit" className={kind}>{label}</button>
    </form>
  );
}

export default async function EndpointPage({ params, searchParams }: {
  params: Promise<{ id: string }>; searchParams: Promise<{ notice?: string; code?: string; page?: string }>;
}) {
  const lf = await requireClient();
  const id = requireId((await params).id);
  const query = await searchParams;
  const notice = noticeFor(query.notice, query.code);
  const page = pageParam(query.page);
  const [endpoint, deliveries] = await Promise.all([
    load(() => lf.webhooks.retrieve(id)),
    load(() => lf.webhooks.deliveries(id, { page, size: 25 })),
  ]);
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="mono" style={{ wordBreak: "break-all" }}>{endpoint.url}</h1>
          <p>
            {endpoint.enabled ? <span className="badge ok">Active</span> : <span className="badge bad">Disabled</span>}
            {" "}· {endpoint.eventTypes.join(", ")} · created {when(endpoint.createdAt)}
          </p>
        </div>
      </div>
      {notice && <div className={`alert ${notice.kind}`} role={notice.kind === "error" ? "alert" : "status"}>{notice.text}</div>}
      {!endpoint.enabled && (
        <div className="alert error" role="alert">
          Disabled {endpoint.disabledAt ? `on ${when(endpoint.disabledAt)}` : ""} after repeated failed deliveries.
          Fix the receiver, then enable it and retry the failed deliveries.
        </div>
      )}
      <section className="card">
        <div className="actions">
          {endpoint.enabled ? <Op id={id} op="test" label="Send test event" /> : <Op id={id} op="enable" label="Enable" kind="" />}
          <RotateSecretForm endpointId={id} />
          <Op id={id} op="delete" label="Delete endpoint" kind="danger" />
        </div>
      </section>
      <section className="card">
        <h2>Deliveries</h2>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Created</th><th>Event</th><th>Status</th><th className="num">Attempts</th><th>Last result</th><th /></tr></thead>
            <tbody>
              {deliveries.items.map((d) => (
                <tr key={d.id}>
                  <td className="muted">{when(d.createdAt)}</td>
                  <td className="mono">{shortId(d.eventId)}</td>
                  <td><span className={`badge ${STATUS_BADGE[d.status] ?? ""}`}>{d.status}</span>
                    {d.nextAttemptAt && <div className="muted">next try {when(d.nextAttemptAt)}</div>}</td>
                  <td className="num">{d.attempts}</td>
                  <td>{d.lastStatusCode ? `HTTP ${d.lastStatusCode}` : ""} {d.lastError && <span className="muted">{d.lastError}</span>}</td>
                  <td>{(d.status === "FAILED" || d.status === "CANCELLED") && endpoint.enabled &&
                    <Op id={id} op="retry" label="Retry" deliveryId={d.id} />}</td>
                </tr>
              ))}
              {deliveries.items.length === 0 && <tr><td colSpan={6} className="muted">No deliveries yet.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pager">
          {page > 0 && <Link href={`/webhooks/${id}?page=${page - 1}`}>← Newer</Link>}
          {page + 1 < deliveries.totalPages && <Link href={`/webhooks/${id}?page=${page + 1}`}>Older →</Link>}
        </div>
      </section>
    </>
  );
}

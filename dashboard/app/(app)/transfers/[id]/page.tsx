import Link from "next/link";
import { load, requireClient } from "@/lib/auth.ts";
import { money, when } from "@/lib/format.ts";

export const metadata = { title: "Transfer" };

export default async function TransferPage({ params }: { params: Promise<{ id: string }> }) {
  const lf = await requireClient();
  const { id } = await params;
  const t = await load(() => lf.transfers.retrieve(id));
  const [from, to] = await Promise.all([
    load(() => lf.accounts.retrieve(t.fromAccountId)),
    load(() => lf.accounts.retrieve(t.toAccountId)),
  ]);
  return (
    <>
      <div className="page-head">
        <div><h1>{money(t.amount, t.currency)}</h1><p><span className="badge ok">{t.status}</span> · <code>{t.id}</code></p></div>
      </div>
      <section className="card">
        <table>
          <tbody>
            <tr><th>From</th><td><Link href={`/accounts/${from.id}`}>{from.name}</Link></td></tr>
            <tr><th>To</th><td><Link href={`/accounts/${to.id}`}>{to.name}</Link></td></tr>
            <tr><th>Posted</th><td>{when(t.createdAt)}</td></tr>
            <tr><th>Reference</th><td>{t.reference ?? <span className="muted">—</span>}</td></tr>
            <tr><th>Description</th><td>{t.description ?? <span className="muted">—</span>}</td></tr>
          </tbody>
        </table>
      </section>
      <p className="muted">Two postings were written: −{money(t.amount, t.currency)} from {from.name} and +{money(t.amount, t.currency)} to {to.name}. Postings can&apos;t be edited or deleted; a correction is a new transfer back.</p>
    </>
  );
}

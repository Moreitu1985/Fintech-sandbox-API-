import Link from "next/link";
import { load, pageParam, requireClient, requireId } from "@/lib/auth.ts";
import { money, shortId, when } from "@/lib/format.ts";

export const metadata = { title: "Account" };

export default async function AccountPage({ params, searchParams }: {
  params: Promise<{ id: string }>; searchParams: Promise<{ page?: string }>;
}) {
  const lf = await requireClient();
  const id = requireId((await params).id);
  const page = pageParam((await searchParams).page);
  const [account, entries] = await Promise.all([
    load(() => lf.accounts.retrieve(id)),
    load(() => lf.accounts.entries(id, { page, size: 25 })),
  ]);
  return (
    <>
      <div className="page-head">
        <div>
          <h1>{account.name}</h1>
          <p>{account.allowNegative ? "Funding account" : "Customer account"} · {account.currency} · <code>{account.id}</code></p>
        </div>
        <Link className="button" href={`/transfers/new?from=${account.id}`}>Transfer from here</Link>
      </div>
      <div className="grid">
        <section className="card">
          <div className="stat-label">Balance</div>
          <div className="stat" data-testid="balance">{money(account.balance, account.currency)}</div>
        </section>
        <section className="card">
          <div className="stat-label">Entries</div>
          <div className="stat">{entries.totalItems}</div>
        </section>
      </div>
      <section className="card">
        <h2>Statement</h2>
        <div className="table-wrap">
          <table>
            <thead><tr><th>When</th><th>Transfer</th><th className="num">Amount</th></tr></thead>
            <tbody>
              {entries.items.map((e) => (
                <tr key={`${e.transactionId}-${e.amount}`}>
                  <td className="muted">{when(e.createdAt)}</td>
                  <td><Link href={`/transfers/${e.transactionId}`} className="mono">{shortId(e.transactionId)}</Link></td>
                  <td className={`num ${e.amount < 0 ? "neg" : "pos"}`}>{money(e.amount, account.currency)}</td>
                </tr>
              ))}
              {entries.items.length === 0 && <tr><td colSpan={3} className="muted">No money has moved yet.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pager">
          {page > 0 && <Link href={`/accounts/${id}?page=${page - 1}`}>← Newer</Link>}
          {page + 1 < entries.totalPages && <Link href={`/accounts/${id}?page=${page + 1}`}>Older →</Link>}
        </div>
      </section>
    </>
  );
}

import Link from "next/link";
import { load, requireClient } from "@/lib/auth.ts";
import { allAccounts } from "@/lib/data.ts";
import { money } from "@/lib/format.ts";

export const metadata = { title: "Overview" };

export default async function Overview() {
  const lf = await requireClient();
  const [integrity, { items: accounts, truncated }, endpoints] = await Promise.all([
    load(() => lf.ledger.integrity()),
    load(() => allAccounts(lf)),
    load(() => lf.webhooks.list()),
  ]);
  const byCurrency = new Map<string, { customer: number; funding: number }>();
  for (const a of accounts) {
    const t = byCurrency.get(a.currency) ?? { customer: 0, funding: 0 };
    if (a.allowNegative) t.funding += a.balance; else t.customer += a.balance;
    byCurrency.set(a.currency, t);
  }
  const disabled = endpoints.filter((e) => !e.enabled).length;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Overview</h1>
          <p>Your tenant at a glance. Every figure comes live from the ledger.</p>
        </div>
        <Link className="button" href="/transfers/new">New transfer</Link>
      </div>

      <div className="grid">
        <section className="card" aria-labelledby="integrity">
          <div className="stat-label" id="integrity">Ledger integrity</div>
          <div className="stat">
            {integrity.healthy ? <span className="badge ok">Healthy</span> : <span className="badge bad">Problem found</span>}
          </div>
          <p className="muted" style={{ marginTop: 8 }}>
            Postings sum to {integrity.postingsSum}; {integrity.accountsChecked} balances checked;
            audit chain {integrity.auditChainValid ? "intact" : "BROKEN"} ({integrity.auditEventsChecked} events).
          </p>
        </section>
        <section className="card">
          <div className="stat-label">Accounts</div>
          <div className="stat">{accounts.length}{truncated ? "+" : ""}</div>
          <p className="muted"><Link href="/accounts">View all</Link></p>
        </section>
        <section className="card">
          <div className="stat-label">Webhook endpoints</div>
          <div className="stat">{endpoints.length}</div>
          <p className="muted">
            {disabled > 0 ? <span className="badge warn">{disabled} disabled</span> : "All active"} · <Link href="/webhooks">Manage</Link>
          </p>
        </section>
      </div>

      <section className="card">
        <h2>Money held, by currency</h2>
        {byCurrency.size === 0 ? (
          <p className="muted">No accounts yet. <Link href="/accounts">Open one</Link>.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Currency</th><th className="num">Customer accounts</th><th className="num">Funding accounts</th></tr></thead>
              <tbody>
                {[...byCurrency].map(([currency, t]) => (
                  <tr key={currency}>
                    <td>{currency}</td>
                    <td className="num">{money(t.customer, currency)}</td>
                    <td className="num">{money(t.funding, currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="muted" style={{ marginTop: 10 }}>
          {truncated
            ? "Showing the first 1,000 accounts only, so these totals are partial."
            : "Double entry: the two columns always add up to zero, because every cent in a customer account came from a funding account."}
        </p>
      </section>
    </>
  );
}

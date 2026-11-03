import Link from "next/link";
import { load, pageParam, requireClient } from "@/lib/auth.ts";
import { money, when } from "@/lib/format.ts";
import { NewAccountForm } from "./new-account-form.tsx";

export const metadata = { title: "Accounts" };

export default async function AccountsPage({ searchParams }: { searchParams: Promise<{ page?: string }> }) {
  const lf = await requireClient();
  const page = pageParam((await searchParams).page);
  const accounts = await load(() => lf.accounts.list({ page, size: 25 }));
  return (
    <>
      <div className="page-head">
        <div><h1>Accounts</h1><p>{accounts.totalItems} in total.</p></div>
      </div>
      <section className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>Opened</th><th className="num">Balance</th></tr></thead>
            <tbody>
              {accounts.items.map((a) => (
                <tr key={a.id}>
                  <td><Link href={`/accounts/${a.id}`}>{a.name}</Link></td>
                  <td>{a.allowNegative ? <span className="badge">Funding</span> : <span className="badge ok">Customer</span>}</td>
                  <td className="muted">{when(a.createdAt)}</td>
                  <td className="num">{money(a.balance, a.currency)}</td>
                </tr>
              ))}
              {accounts.items.length === 0 && <tr><td colSpan={4} className="muted">No accounts yet.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="pager">
          {page > 0 && <Link href={`/accounts?page=${page - 1}`}>← Previous</Link>}
          {page + 1 < accounts.totalPages && <Link href={`/accounts?page=${page + 1}`}>Next →</Link>}
        </div>
      </section>
      <section className="card">
        <h2>Open an account</h2>
        <NewAccountForm />
      </section>
    </>
  );
}

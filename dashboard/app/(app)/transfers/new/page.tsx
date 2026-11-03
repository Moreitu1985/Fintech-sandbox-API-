import { randomUUID } from "node:crypto";
import Link from "next/link";
import { load, requireClient } from "@/lib/auth.ts";
import { allAccounts } from "@/lib/data.ts";
import { money } from "@/lib/format.ts";
import { TransferForm } from "./transfer-form.tsx";

export const metadata = { title: "New transfer" };

export default async function NewTransferPage({ searchParams }: { searchParams: Promise<{ from?: string }> }) {
  const lf = await requireClient();
  const accounts = await load(() => allAccounts(lf));
  const { from } = await searchParams;
  const options = accounts.map((a) => ({
    id: a.id,
    label: `${a.name} · ${money(a.balance, a.currency)}${a.allowNegative ? " · funding" : ""}`,
  }));
  return (
    <>
      <div className="page-head">
        <div>
          <h1>New transfer</h1>
          <p>Moves money between two of your accounts in the same currency. Both sides post together or not at all.</p>
        </div>
      </div>
      <section className="card">
        {accounts.length < 2 ? (
          <p className="muted">You need at least two accounts. <Link href="/accounts">Open accounts</Link>.</p>
        ) : (
          <TransferForm accounts={options} initialKey={randomUUID()} {...(from ? { defaultFrom: from } : {})} />
        )}
      </section>
    </>
  );
}

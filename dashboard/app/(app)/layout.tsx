import Link from "next/link";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { readSession } from "@/lib/auth.ts";
import { logout } from "./actions.ts";

export const dynamic = "force-dynamic";

export default async function AppLayout({ children }: { children: ReactNode }) {
  if (!(await readSession())) redirect("/login");
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">Ledger<span>Forge</span></div>
        <nav className="nav" aria-label="Main">
          <Link href="/">Overview</Link>
          <Link href="/accounts">Accounts</Link>
          <Link href="/transfers/new">New transfer</Link>
          <Link href="/webhooks">Webhooks</Link>
        </nav>
        <form action={logout}>
          <button type="submit" className="secondary">Sign out</button>
        </form>
      </aside>
      <main className="main">{children}</main>
    </div>
  );
}

import "server-only";
import type { Account, LedgerForge } from "@ledgerforge/sdk";

/** Every account (the dashboard caps it to keep pages fast; the API itself has no such limit). */
export async function allAccounts(lf: LedgerForge, cap = 1000): Promise<Account[]> {
  const out: Account[] = [];
  for await (const a of lf.accounts.listAll({ pageSize: 100 })) {
    out.push(a);
    if (out.length >= cap) break;
  }
  return out;
}

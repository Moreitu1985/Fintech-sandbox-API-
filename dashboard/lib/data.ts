import "server-only";
import type { Account, LedgerForge } from "@ledgerforge/sdk";

export const ACCOUNT_CAP = 1000;

/** Accounts for the overview and the transfer form, up to a cap that keeps those pages fast. */
export async function allAccounts(lf: LedgerForge): Promise<{ items: Account[]; truncated: boolean }> {
  const items: Account[] = [];
  for await (const a of lf.accounts.listAll({ pageSize: 100 })) {
    if (items.length === ACCOUNT_CAP) return { items, truncated: true };
    items.push(a);
  }
  return { items, truncated: false };
}

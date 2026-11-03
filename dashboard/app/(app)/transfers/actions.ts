"use server";

import { LedgerForgeError } from "@ledgerforge/sdk";
import { revalidatePath } from "next/cache";
import { actionError, isId, requireClient } from "@/lib/auth.ts";
import { parseAmount } from "@/lib/format.ts";

export interface TransferValues {
  from: string;
  to: string;
  amount: string;
  reference: string;
  description: string;
}

/**
 * The outcome always names the idempotency key it used, so the form only moves on to a new key once
 * that exact key's payment is settled.
 *
 * - `posted`: done (or `replayed`: an earlier submit with this key had already done it). New key next.
 * - `keyUsed`: this key already paid for something else (the form changed after a submit that went
 *   through). Nothing moved now; new key next.
 * - `failed`: nothing was decided (a validation or business error, or the API couldn't be reached).
 *   Same key next, with the values kept, so resubmitting is still the same payment.
 */
export type TransferOutcome =
  | { kind: "posted"; key: string; id: string; replayed: boolean }
  | { kind: "keyUsed"; key: string; error: string }
  | { kind: "failed"; key: string; error: string; values: TransferValues };

export async function createTransfer(_prev: TransferOutcome | null, form: FormData): Promise<TransferOutcome> {
  const lf = await requireClient();
  const values: TransferValues = {
    from: String(form.get("from") ?? ""),
    to: String(form.get("to") ?? ""),
    amount: String(form.get("amount") ?? "").trim(),
    reference: String(form.get("reference") ?? "").trim(),
    description: String(form.get("description") ?? "").trim(),
  };
  const key = String(form.get("idempotencyKey") ?? "");
  const fail = (error: string): TransferOutcome => ({ kind: "failed", key, error, values });

  if (!/^[A-Za-z0-9_-]{8,64}$/.test(key)) return fail("This form is out of date. Reload the page and try again.");
  if (!isId(values.from) || !isId(values.to)) return fail("Choose both accounts.");
  if (values.from === values.to) return fail("Choose two different accounts.");
  try {
    const from = await lf.accounts.retrieve(values.from);
    const digits = new Intl.NumberFormat("en", { style: "currency", currency: from.currency })
      .resolvedOptions().maximumFractionDigits ?? 2;
    const amount = parseAmount(values.amount, digits);
    if (amount === null) {
      return fail(`Enter an amount greater than zero with at most ${digits} decimal places, using a point (e.g. 250.50).`);
    }
    // The key came with the form, so submitting twice (a double click, a resend after a network error)
    // returns the first transfer instead of making a second one.
    const result = await lf.transfers.create({
      fromAccountId: values.from, toAccountId: values.to, amount, currency: from.currency,
      ...(values.reference ? { reference: values.reference } : {}),
      ...(values.description ? { description: values.description } : {}),
    }, { idempotencyKey: key });
    revalidatePath("/");
    revalidatePath("/accounts");
    return { kind: "posted", key, id: result.id, replayed: result.replayed };
  } catch (e) {
    if (e instanceof LedgerForgeError && e.code === "IDEMPOTENCY_KEY_REUSED") {
      return { kind: "keyUsed", key, error: "An earlier submit of this form already went through with different "
        + "details, so nothing was sent now. Check the accounts' statements before trying again." };
    }
    return fail(await actionError(e));
  }
}

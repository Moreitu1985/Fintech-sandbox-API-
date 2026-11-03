"use server";

import { revalidatePath } from "next/cache";
import { describe, requireClient, type ActionResult } from "@/lib/auth.ts";
import { parseAmount } from "@/lib/format.ts";

export interface TransferDone {
  id: string;
  replayed: boolean;
}

export async function createTransfer(_prev: ActionResult<TransferDone> | null, form: FormData)
  : Promise<ActionResult<TransferDone>> {
  const lf = await requireClient();
  const fromAccountId = String(form.get("from") ?? "");
  const toAccountId = String(form.get("to") ?? "");
  const idempotencyKey = String(form.get("idempotencyKey") ?? "");
  const reference = String(form.get("reference") ?? "").trim();
  const description = String(form.get("description") ?? "").trim();
  if (!fromAccountId || !toAccountId) return { ok: false, error: "Choose both accounts." };
  if (fromAccountId === toAccountId) return { ok: false, error: "Choose two different accounts." };
  try {
    const from = await lf.accounts.retrieve(fromAccountId);
    const digits = new Intl.NumberFormat("en", { style: "currency", currency: from.currency })
      .resolvedOptions().maximumFractionDigits ?? 2;
    const amount = parseAmount(String(form.get("amount") ?? ""), digits);
    if (amount === null) {
      return { ok: false, error: `Enter an amount greater than zero, with at most ${digits} decimal places.` };
    }
    // The key came with the form when the page loaded, so submitting twice (a double click, a resend after
    // a network error) returns the first transfer instead of making a second one.
    const result = await lf.transfers.create({
      fromAccountId, toAccountId, amount, currency: from.currency,
      ...(reference ? { reference } : {}), ...(description ? { description } : {}),
    }, { idempotencyKey });
    revalidatePath("/");
    revalidatePath("/accounts");
    return { ok: true, value: { id: result.id, replayed: result.replayed } };
  } catch (e) {
    return { ok: false, error: describe(e) };
  }
}

"use server";

import { revalidatePath } from "next/cache";
import { describe, requireClient, type ActionResult } from "@/lib/auth.ts";

export async function createAccount(_prev: ActionResult<string> | null, form: FormData): Promise<ActionResult<string>> {
  const lf = await requireClient();
  const name = String(form.get("name") ?? "").trim();
  const currency = String(form.get("currency") ?? "").trim().toUpperCase();
  if (!name) return { ok: false, error: "Give the account a name." };
  try {
    const account = await lf.accounts.create({ name, currency, allowNegative: form.get("allowNegative") === "on" });
    revalidatePath("/accounts");
    return { ok: true, value: account.id };
  } catch (e) {
    return { ok: false, error: describe(e) };
  }
}

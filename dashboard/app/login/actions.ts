"use server";

import { ConnectionError, LedgerForgeError } from "@ledgerforge/sdk";
import { redirect } from "next/navigation";
import { clientFor, startSession } from "@/lib/auth.ts";

export interface LoginState {
  error?: string;
}

export async function login(_prev: LoginState, form: FormData): Promise<LoginState> {
  const apiKey = String(form.get("apiKey") ?? "").trim();
  if (!/^lf_[a-z]+_[0-9a-f]{12}_[0-9a-f]{64}$/.test(apiKey)) {
    return { error: "That doesn't look like a LedgerForge API key (lf_test_…)." };
  }
  try {
    // Proves the key works (and is still active) before it goes into a session.
    await clientFor(apiKey).ledger.integrity();
  } catch (e) {
    if (e instanceof LedgerForgeError && (e.status === 401 || e.status === 403)) {
      return { error: "That API key wasn't accepted. Check it, or ask for a new one." };
    }
    if (e instanceof ConnectionError) return { error: "The LedgerForge API can't be reached right now." };
    throw e;
  }
  await startSession(apiKey);
  redirect("/");
}

"use client";

import Link from "next/link";
import { useActionState } from "react";
import type { ActionResult } from "@/lib/auth.ts";
import { createAccount } from "./actions.ts";

export function NewAccountForm() {
  const [state, action, pending] = useActionState<ActionResult<string> | null, FormData>(createAccount, null);
  return (
    <form action={action} className="stack">
      {state?.ok === false && <div className="alert error" role="alert">{state.error}</div>}
      {state?.ok && (
        <div className="alert success" role="status">
          Account opened. <Link href={`/accounts/${state.value}`}>View it</Link>
        </div>
      )}
      <label>Name<input name="name" required maxLength={100} placeholder="e.g. Customer wallet" /></label>
      <label>Currency<input name="currency" required defaultValue="ZAR" pattern="[A-Za-z]{3}" maxLength={3} /></label>
      <label className="checkbox">
        <input type="checkbox" name="allowNegative" /> Funding account (may go negative; use for treasury or settlement)
      </label>
      <div><button type="submit" disabled={pending}>{pending ? "Opening…" : "Open account"}</button></div>
    </form>
  );
}

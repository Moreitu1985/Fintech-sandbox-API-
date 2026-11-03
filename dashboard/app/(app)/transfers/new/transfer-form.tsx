"use client";

import Link from "next/link";
import { useActionState, useEffect, useState } from "react";
import type { ActionResult } from "@/lib/auth.ts";
import { createTransfer, type TransferDone } from "../actions.ts";

export interface AccountOption {
  id: string;
  label: string;
}

export function TransferForm({ accounts, initialKey, defaultFrom }: {
  accounts: AccountOption[]; initialKey: string; defaultFrom?: string;
}) {
  const [state, action, pending] = useActionState<ActionResult<TransferDone> | null, FormData>(createTransfer, null);
  const [key, setKey] = useState(initialKey);
  const [doneKey, setDoneKey] = useState<string | null>(null);

  // A completed transfer gets a fresh key for the next one; a failed one keeps its key, so fixing the
  // form and resubmitting is still the same logical payment.
  useEffect(() => {
    if (state?.ok && doneKey !== key) {
      setDoneKey(key);
      setKey(crypto.randomUUID());
    }
  }, [state, key, doneKey]);

  return (
    <form action={action} className="stack">
      {state?.ok === false && <div className="alert error" role="alert">{state.error}</div>}
      {state?.ok && (
        <div className="alert success" role="status">
          {state.value.replayed
            ? "This transfer had already been made, so no money moved a second time. "
            : "Transfer posted. "}
          <Link href={`/transfers/${state.value.id}`}>View transfer</Link>
        </div>
      )}
      <input type="hidden" name="idempotencyKey" value={key} />
      <label>From
        <select name="from" required defaultValue={defaultFrom ?? ""}>
          <option value="" disabled>Choose an account</option>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
        </select>
      </label>
      <label>To
        <select name="to" required defaultValue="">
          <option value="" disabled>Choose an account</option>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
        </select>
      </label>
      <label>Amount
        <input name="amount" required inputMode="decimal" placeholder="250.00" autoComplete="off" />
        <span className="hint">In the account&apos;s currency, e.g. 250.00. Stored exactly, in cents.</span>
      </label>
      <label>Reference <span className="hint">Optional: letters, digits and . _ : -</span>
        <input name="reference" maxLength={64} pattern="[A-Za-z0-9._:\-]*" />
      </label>
      <label>Description <span className="hint">Optional</span>
        <input name="description" maxLength={140} />
      </label>
      <div><button type="submit" disabled={pending}>{pending ? "Posting…" : "Post transfer"}</button></div>
      <p className="muted mono" style={{ fontSize: "0.8rem" }}>Idempotency key: {key}</p>
    </form>
  );
}

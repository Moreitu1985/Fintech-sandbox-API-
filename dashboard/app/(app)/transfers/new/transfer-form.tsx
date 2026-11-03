"use client";

import Link from "next/link";
import { useActionState, useEffect, useState } from "react";
import { createTransfer, type TransferOutcome } from "../actions.ts";

export interface AccountOption {
  id: string;
  label: string;
}

// The key of a submit whose outcome we haven't seen yet (the response was lost, or the page re-rendered
// mid-flight). Kept for this tab, so retrying after that is still the same payment.
const PENDING = "ledgerforge.pendingTransferKey";

function readPending(): string | null {
  try {
    return sessionStorage.getItem(PENDING);
  } catch {
    return null;
  }
}

function writePending(key: string | null) {
  try {
    if (key) sessionStorage.setItem(PENDING, key); else sessionStorage.removeItem(PENDING);
  } catch {
    // storage unavailable: the key still lives in this page
  }
}

export function TransferForm({ accounts, initialKey, defaultFrom }: {
  accounts: AccountOption[]; initialKey: string; defaultFrom?: string;
}) {
  const [state, action, pending] = useActionState<TransferOutcome | null, FormData>(createTransfer, null);
  const [key, setKey] = useState(initialKey);

  // After a remount, carry on with an unsettled key rather than a fresh one.
  useEffect(() => {
    const unsettled = readPending();
    if (unsettled) setKey(unsettled);
  }, []);

  // Move to a new key only once this exact key is settled (posted, replayed, or spent elsewhere).
  useEffect(() => {
    if (state && state.kind !== "failed" && state.key === key) {
      writePending(null);
      setKey(crypto.randomUUID());
    }
  }, [state, key]);

  const values = state?.kind === "failed" ? state.values : undefined;
  // Remount the inputs with the submitted values after a failure (React clears a form after its action).
  const fieldsKey = state ? `${state.kind}-${state.key}-${values ? JSON.stringify(values) : ""}` : "initial";

  return (
    <form action={action} onSubmit={() => writePending(key)} className="stack">
      {state?.kind === "failed" && <div className="alert error" role="alert">{state.error}</div>}
      {state?.kind === "keyUsed" && <div className="alert error" role="alert">{state.error}</div>}
      {state?.kind === "posted" && (
        <div className="alert success" role="status">
          {state.replayed
            ? "This transfer had already been made, so no money moved a second time. "
            : "Transfer posted. "}
          <Link href={`/transfers/${state.id}`}>View transfer</Link>
        </div>
      )}
      <input type="hidden" name="idempotencyKey" value={key} />
      <div key={fieldsKey} className="stack" style={{ maxWidth: "none" }}>
        <label>From
          <select name="from" required defaultValue={values?.from ?? defaultFrom ?? ""}>
            <option value="" disabled>Choose an account</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
          </select>
        </label>
        <label>To
          <select name="to" required defaultValue={values?.to ?? ""}>
            <option value="" disabled>Choose an account</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
          </select>
        </label>
        <label>Amount
          <input name="amount" required inputMode="decimal" placeholder="250.00" autoComplete="off"
                 defaultValue={values?.amount ?? ""} />
          <span className="hint">In the account&apos;s currency, e.g. 250.00. Stored exactly, in cents.</span>
        </label>
        <label>Reference <span className="hint">Optional: letters, digits and . _ : -</span>
          <input name="reference" maxLength={64} pattern="[A-Za-z0-9._:\-]*" defaultValue={values?.reference ?? ""} />
        </label>
        <label>Description <span className="hint">Optional</span>
          <input name="description" maxLength={140} defaultValue={values?.description ?? ""} />
        </label>
      </div>
      <div><button type="submit" disabled={pending}>{pending ? "Posting…" : "Post transfer"}</button></div>
      <p className="muted mono" style={{ fontSize: "0.8rem" }} data-testid="idempotency-key">Idempotency key: {key}</p>
    </form>
  );
}

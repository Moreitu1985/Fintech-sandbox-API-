"use client";

import Link from "next/link";
import { useActionState } from "react";
import type { ActionResult } from "@/lib/auth.ts";
import { createEndpoint, rotateSecret, type CreatedEndpoint } from "./actions.ts";

function Secret({ value }: { value: string }) {
  return (
    <>
      <div className="secret" data-testid="webhook-secret">{value}</div>
      <p className="muted">Copy it now: it won&apos;t be shown again. Verify deliveries with it (the SDK&apos;s <code>verifyWebhook</code>).</p>
    </>
  );
}

export function NewEndpointForm() {
  const [state, action, pending] = useActionState<ActionResult<CreatedEndpoint> | null, FormData>(createEndpoint, null);
  return (
    <form action={action} className="stack">
      {state?.ok === false && <div className="alert error" role="alert">{state.error}</div>}
      {state?.ok && (
        <div className="alert success" role="status">
          Endpoint registered. <Link href={`/webhooks/${state.value.id}`}>View it</Link>
          <Secret value={state.value.secret} />
        </div>
      )}
      <label>URL
        <input name="url" type="url" required maxLength={2048} placeholder="https://example.com/webhooks/ledgerforge" />
        <span className="hint">HTTPS on port 443, reachable from the internet.</span>
      </label>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend style={{ fontWeight: 550, fontSize: "0.9rem", marginBottom: 4 }}>Events</legend>
        <label className="checkbox"><input type="checkbox" name="transfer.posted" defaultChecked /> transfer.posted</label>
        <label className="checkbox"><input type="checkbox" name="account.created" /> account.created</label>
      </fieldset>
      <label>Description <span className="hint">Optional</span><input name="description" maxLength={140} /></label>
      <div><button type="submit" disabled={pending}>{pending ? "Registering…" : "Register endpoint"}</button></div>
    </form>
  );
}

export function RotateSecretForm({ endpointId }: { endpointId: string }) {
  const [state, action, pending] = useActionState<ActionResult<string> | null, FormData>(rotateSecret, null);
  return (
    <form action={action}>
      <input type="hidden" name="endpointId" value={endpointId} />
      {state?.ok === false && <div className="alert error" role="alert">{state.error}</div>}
      {state?.ok && (
        <div className="alert success" role="status">
          New secret issued. The old one stopped working just now.
          <Secret value={state.value} />
        </div>
      )}
      <button type="submit" className="secondary" disabled={pending}>{pending ? "Rotating…" : "Rotate secret"}</button>
    </form>
  );
}

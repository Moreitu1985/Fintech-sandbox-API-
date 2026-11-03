"use client";

import { useActionState } from "react";
import { login, type LoginState } from "./actions.ts";

export function LoginForm() {
  const [state, action, pending] = useActionState<LoginState, FormData>(login, {});
  return (
    <form action={action} className="stack">
      {state.error && <div className="alert error" role="alert">{state.error}</div>}
      <label>
        API key
        <input name="apiKey" type="password" autoComplete="off" spellCheck={false} required placeholder="lf_test_…" />
        <span className="hint">Kept encrypted on the dashboard&apos;s server for 8 hours. Your browser never sees it again.</span>
      </label>
      <button type="submit" disabled={pending}>{pending ? "Checking…" : "Sign in"}</button>
    </form>
  );
}

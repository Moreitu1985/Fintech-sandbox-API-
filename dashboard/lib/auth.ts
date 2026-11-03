import "server-only";
import { ConnectionError, LedgerForge, LedgerForgeError } from "@ledgerforge/sdk";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { seal, sessionKey, SESSION_COOKIE, SESSION_TTL_SECONDS, unseal, type Session } from "./session.ts";

export function apiUrl(): string {
  return process.env["LEDGERFORGE_API_URL"] ?? "http://localhost:8080";
}

export function clientFor(apiKey: string): LedgerForge {
  return new LedgerForge({ baseUrl: apiUrl(), apiKey, timeoutMs: 15_000 });
}

export async function readSession(): Promise<Session | null> {
  const jar = await cookies();
  return unseal(jar.get(SESSION_COOKIE)?.value, sessionKey());
}

/** For pages and actions: the signed-in tenant's client, or a redirect to sign in. */
export async function requireClient(): Promise<LedgerForge> {
  const session = await readSession();
  if (!session) redirect("/login");
  return clientFor(session.apiKey);
}

export async function startSession(apiKey: string): Promise<void> {
  const jar = await cookies();
  jar.set(SESSION_COOKIE, seal({ apiKey, iat: Math.floor(Date.now() / 1000) }, sessionKey()), {
    httpOnly: true,                                    // scripts in the page can't read it
    secure: process.env.NODE_ENV === "production",     // HTTPS only (browsers treat localhost as secure)
    sameSite: "strict",                                // never sent on requests started by other sites
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  });
}

export async function endSession(): Promise<void> {
  (await cookies()).delete(SESSION_COOKIE);
}

/**
 * Runs an API call for a page: a revoked key ends the session, a missing resource is a 404 page, and
 * anything else propagates to the error page.
 */
export async function load<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (e) {
    if (e instanceof LedgerForgeError && e.status === 401) redirect("/session-expired");
    if (e instanceof LedgerForgeError && e.status === 404) notFound();
    throw e;
  }
}

/** What a form action reports back. */
export type ActionResult<T = undefined> = { ok: true; value: T } | { ok: false; error: string };

/** Turns an API error into something to show next to a form. */
export function describe(e: unknown): string {
  if (e instanceof LedgerForgeError) {
    if (e.status === 401) return "Your session has expired. Sign in again.";
    const fields = e.errors.map((f) => `${f.field}: ${f.message}`).join("; ");
    return `${e.detail ?? e.code}${fields ? ` (${fields})` : ""} [${e.code}${e.requestId ? `, request ${e.requestId}` : ""}]`;
  }
  if (e instanceof ConnectionError) return "The LedgerForge API can't be reached right now. Try again shortly.";
  throw e;
}

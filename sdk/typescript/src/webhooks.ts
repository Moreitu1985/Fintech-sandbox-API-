import { WebhookVerificationError } from "./errors.ts";
import type { WebhookEvent } from "./types.ts";

/** Header shapes from fetch/Request (Headers), Node's http module (string | string[]) or a plain object. */
export type HeaderSource = Headers | Record<string, string | string[] | undefined>;

export interface VerifyOptions {
  /** Reject events whose timestamp is further than this from now (replay protection). Default 300 s. */
  toleranceSeconds?: number;
  /** Current time in seconds, for tests. */
  now?: number;
}

const SECRET_PREFIX = "whsec_";
const encoder = new TextEncoder();

/**
 * Verifies a LedgerForge webhook (Standard Webhooks format) and returns the parsed event.
 *
 * Pass the **raw request body exactly as received**: parsing and re-serialising JSON changes the bytes and
 * the signature won't match. Throws {@link WebhookVerificationError} for anything that isn't a genuine,
 * fresh event; respond 400 and don't process it.
 *
 * ```ts
 * app.post("/webhooks/ledgerforge", express.raw({ type: "application/json" }), async (req, res) => {
 *   const event = await verifyWebhook(req.body, req.headers, process.env.LEDGERFORGE_WEBHOOK_SECRET!);
 *   if (await alreadyProcessed(event.id)) return res.sendStatus(200); // retries reuse the id
 *   ...
 * });
 * ```
 */
export async function verifyWebhook(payload: string | Uint8Array, headers: HeaderSource, secret: string,
                                    options: VerifyOptions = {}): Promise<WebhookEvent> {
  const id = header(headers, "webhook-id");
  const timestamp = header(headers, "webhook-timestamp");
  const signatures = header(headers, "webhook-signature");
  if (!id || !timestamp || !signatures) {
    throw new WebhookVerificationError("Missing webhook-id, webhook-timestamp or webhook-signature header");
  }
  if (!/^\d{1,12}$/.test(timestamp)) {
    throw new WebhookVerificationError("Invalid webhook-timestamp");
  }
  const sentAt = Number(timestamp);
  const now = options.now ?? Math.floor(Date.now() / 1000);
  const tolerance = options.toleranceSeconds ?? 300;
  if (Math.abs(now - sentAt) > tolerance) {
    throw new WebhookVerificationError("Webhook timestamp is outside the allowed tolerance (possible replay)");
  }

  const body = typeof payload === "string" ? payload : new TextDecoder("utf-8", { fatal: true }).decode(payload);
  const expected = await hmac(secret, `${id}.${timestamp}.${body}`);
  let valid = false;
  for (const candidate of signatures.split(" ")) {
    const [version, value] = candidate.split(",", 2);
    if (version !== "v1" || !value) continue;
    let given: Uint8Array;
    try {
      given = base64Decode(value);
    } catch {
      continue;
    }
    valid = timingSafeEqual(expected, given) || valid;
  }
  if (!valid) {
    throw new WebhookVerificationError("No matching signature: wrong secret, or the body was altered");
  }

  let event: unknown;
  try {
    event = JSON.parse(body);
  } catch {
    throw new WebhookVerificationError("Signed payload is not valid JSON");
  }
  if (!event || typeof event !== "object" || (event as { id?: unknown }).id !== id) {
    throw new WebhookVerificationError("Payload id does not match the webhook-id header");
  }
  return event as WebhookEvent;
}

/**
 * Produces the `webhook-signature` header value for a payload, for testing your own receiver.
 */
export async function signWebhook(secret: string, id: string, timestamp: number, payload: string): Promise<string> {
  return "v1," + base64Encode(await hmac(secret, `${id}.${timestamp}.${payload}`));
}

async function hmac(secret: string, message: string): Promise<Uint8Array> {
  if (!secret.startsWith(SECRET_PREFIX)) {
    throw new WebhookVerificationError("Webhook secrets start with whsec_");
  }
  let keyBytes: Uint8Array;
  try {
    keyBytes = base64Decode(secret.slice(SECRET_PREFIX.length));
  } catch {
    throw new WebhookVerificationError("Malformed webhook secret");
  }
  const key = await globalThis.crypto.subtle.importKey("raw", keyBytes as BufferSource,
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await globalThis.crypto.subtle.sign("HMAC", key, encoder.encode(message)));
}

function header(headers: HeaderSource, name: string): string | undefined {
  if (typeof (headers as Headers).get === "function") {
    return (headers as Headers).get(name) ?? undefined;
  }
  const record = headers as Record<string, string | string[] | undefined>;
  const key = Object.keys(record).find((k) => k.toLowerCase() === name);
  const value = key === undefined ? undefined : record[key];
  return Array.isArray(value) ? value[0] : value;
}

/** Compares in time that depends only on the length, so a timing side channel can't reveal the signature. */
function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

function base64Decode(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function base64Encode(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

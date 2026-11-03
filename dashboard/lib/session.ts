// Sessions are stateless: the tenant's API key lives in a cookie, encrypted and authenticated with
// AES-256-GCM under a server-only key. The browser can't read the cookie (httpOnly) or the key inside
// it (encrypted), and can't alter it (authenticated). It is never sent to the browser in any page.
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

export const SESSION_COOKIE = "lf_session";
export const SESSION_TTL_SECONDS = 8 * 60 * 60;

export interface Session {
  apiKey: string;
  /** Issued at, seconds since the epoch. */
  iat: number;
}

const VERSION = "v1";
let devKey: Buffer | undefined;

/** The key from DASHBOARD_SESSION_SECRET (base64, 32 bytes). Required in production. */
export function sessionKey(env: Record<string, string | undefined> = process.env): Buffer {
  const secret = env["DASHBOARD_SESSION_SECRET"];
  if (!secret) {
    if (env["NODE_ENV"] === "production") {
      throw new Error("DASHBOARD_SESSION_SECRET is required in production (generate: openssl rand -base64 32)");
    }
    devKey ??= randomBytes(32); // development: sessions end when the server restarts
    return devKey;
  }
  const key = Buffer.from(secret, "base64");
  if (key.length !== 32) {
    throw new Error("DASHBOARD_SESSION_SECRET must be base64 for exactly 32 bytes");
  }
  return key;
}

export function seal(session: Session, key: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(VERSION));
  const body = Buffer.concat([cipher.update(JSON.stringify(session), "utf8"), cipher.final()]);
  return `${VERSION}.${Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64url")}`;
}

/** The session, or null if the cookie is missing, altered, sealed with another key or expired. */
export function unseal(value: string | undefined, key: Buffer, now = Math.floor(Date.now() / 1000)): Session | null {
  if (!value?.startsWith(`${VERSION}.`)) return null;
  try {
    const raw = Buffer.from(value.slice(VERSION.length + 1), "base64url");
    if (raw.length < 29) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, raw.subarray(0, 12));
    decipher.setAAD(Buffer.from(VERSION));
    decipher.setAuthTag(raw.subarray(12, 28));
    const json = Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString("utf8");
    const session = JSON.parse(json) as Partial<Session>;
    if (typeof session.apiKey !== "string" || typeof session.iat !== "number") return null;
    if (now - session.iat > SESSION_TTL_SECONDS || session.iat > now + 60) return null;
    return { apiKey: session.apiKey, iat: session.iat };
  } catch {
    return null;
  }
}

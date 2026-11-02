import { ConnectionError, LedgerForgeError, RateLimitError, type FieldError } from "./errors.ts";
import type { AccessToken, Scope } from "./types.ts";

export const SDK_VERSION = "0.4.0";

export interface ClientCredentials {
  /** The API key's id (`keyId` when the key was issued). */
  clientId: string;
  /** The API key itself. Sent only to /oauth/token, never with ordinary requests. */
  clientSecret: string;
  /** Ask for less than everything (least privilege), e.g. ["ledger:read"] for a reporting job. */
  scopes?: Scope[];
}

export interface LedgerForgeOptions {
  /** Default http://localhost:8080. */
  baseUrl?: string;
  /** Use an API key directly... */
  apiKey?: string;
  /** ...or OAuth 2.0 client credentials: short-lived tokens are fetched, cached and refreshed for you. */
  clientCredentials?: ClientCredentials;
  /** Per attempt. Default 30 000 ms. */
  timeoutMs?: number;
  /** Retries for safe requests (reads, and transfers thanks to their idempotency key). Default 2. */
  maxRetries?: number;
  /** Custom fetch (tests, proxies, instrumentation). Default: the global fetch. */
  fetch?: typeof fetch;
}

export interface RequestOptions {
  /** Cancels the request (and any retries). */
  signal?: AbortSignal;
  /** Overrides the client's timeout for this call. */
  timeoutMs?: number;
}

interface Call {
  method: "GET" | "POST" | "DELETE";
  path: string;
  query?: Record<string, string | number | undefined>;
  body?: unknown;
  headers?: Record<string, string>;
  /** Repeating the request can't do anything twice (reads, or a write with an idempotency key). */
  retrySafe: boolean;
  options?: RequestOptions | undefined;
}

export interface HttpResult<T> {
  data: T;
  status: number;
  headers: Headers;
}

const RETRYABLE_STATUS = new Set([429, 502, 503, 504]);
const MAX_RETRY_AFTER_SECONDS = 60;

export class HttpClient {
  readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly apiKey: string | undefined;
  private readonly tokens: TokenCache | undefined;

  constructor(options: LedgerForgeOptions) {
    if (!!options.apiKey === !!options.clientCredentials) {
      throw new TypeError("LedgerForge: pass exactly one of apiKey or clientCredentials");
    }
    this.baseUrl = (options.baseUrl ?? "http://localhost:8080").replace(/\/+$/, "");
    const f = options.fetch ?? globalThis.fetch;
    if (typeof f !== "function") {
      throw new TypeError("LedgerForge: no fetch available; pass options.fetch (Node 20+ has one built in)");
    }
    this.fetchImpl = f.bind(globalThis);
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.maxRetries = Math.max(0, options.maxRetries ?? 2);
    this.apiKey = options.apiKey;
    this.tokens = options.clientCredentials
      ? new TokenCache(this.baseUrl, this.fetchImpl, options.clientCredentials, this.timeoutMs)
      : undefined;
  }

  async request<T>(call: Call): Promise<HttpResult<T>> {
    const url = new URL(this.baseUrl + call.path);
    for (const [k, v] of Object.entries(call.query ?? {})) {
      if (v !== undefined) url.searchParams.set(k, String(v));
    }
    let refreshedToken = false;
    for (let attempt = 0; ; attempt++) {
      const auth = this.tokens ? await this.tokens.get(call.options?.signal) : this.apiKey!;
      let response: Response;
      try {
        response = await this.send(url, call, auth);
      } catch (e) {
        if (isAbort(call.options?.signal)) throw e;
        if (call.retrySafe && attempt < this.maxRetries) {
          await sleep(backoff(attempt), call.options?.signal);
          continue;
        }
        throw e;
      }

      // An expired or rotated token: nothing was processed, so get a fresh one and try once more.
      if (response.status === 401 && this.tokens && !refreshedToken) {
        refreshedToken = true;
        this.tokens.invalidate(auth);
        await response.body?.cancel();
        attempt--;
        continue;
      }
      if (RETRYABLE_STATUS.has(response.status) && call.retrySafe && attempt < this.maxRetries) {
        const wait = retryAfterMs(response) ?? backoff(attempt);
        await response.body?.cancel();
        await sleep(wait, call.options?.signal);
        continue;
      }
      if (!response.ok) {
        throw await toError(response);
      }
      const data = (response.status === 204 ? undefined : await parseJson(response)) as T;
      return { data, status: response.status, headers: response.headers };
    }
  }

  private async send(url: URL, call: Call, auth: string): Promise<Response> {
    const timeout = AbortSignal.timeout(call.options?.timeoutMs ?? this.timeoutMs);
    const signal = call.options?.signal ? AbortSignal.any([call.options.signal, timeout]) : timeout;
    const headers: Record<string, string> = {
      Accept: "application/json",
      Authorization: `Bearer ${auth}`,
      ...call.headers,
    };
    let body: string | undefined;
    if (call.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(call.body);
    }
    try {
      return await this.fetchImpl(url, { method: call.method, headers, body: body ?? null, signal });
    } catch (e) {
      if (isAbort(call.options?.signal)) throw e;
      const reason = timeout.aborted ? "timed out" : "failed";
      throw new ConnectionError(`${call.method} ${url.pathname} ${reason}`, { cause: e });
    }
  }
}

/** Caches one access token and refreshes it a minute before it expires; concurrent callers share a refresh. */
class TokenCache {
  private token: string | undefined;
  private expiresAt = 0;
  private inFlight: Promise<string> | undefined;
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly credentials: ClientCredentials;
  private readonly timeoutMs: number;

  constructor(baseUrl: string, fetchImpl: typeof fetch, credentials: ClientCredentials, timeoutMs: number) {
    this.baseUrl = baseUrl;
    this.fetchImpl = fetchImpl;
    this.credentials = credentials;
    this.timeoutMs = timeoutMs;
  }

  async get(signal?: AbortSignal): Promise<string> {
    if (this.token && Date.now() < this.expiresAt) return this.token;
    // The refresh is shared, so it isn't tied to one caller's signal: one caller cancelling must not fail
    // the others. Each caller still stops waiting when its own signal fires.
    this.inFlight ??= this.fetchToken().finally(() => { this.inFlight = undefined; });
    return signal ? raceAbort(this.inFlight, signal) : this.inFlight;
  }

  invalidate(token: string): void {
    if (this.token === token) {
      this.token = undefined;
      this.expiresAt = 0;
    }
  }

  private async fetchToken(): Promise<string> {
    const form = new URLSearchParams({ grant_type: "client_credentials" });
    if (this.credentials.scopes?.length) form.set("scope", this.credentials.scopes.join(" "));
    const basic = btoa(`${encodeURIComponent(this.credentials.clientId)}:${encodeURIComponent(this.credentials.clientSecret)}`);
    const timeout = AbortSignal.timeout(this.timeoutMs);
    let response: Response;
    try {
      response = await this.fetchImpl(this.baseUrl + "/oauth/token", {
        method: "POST",
        headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/x-www-form-urlencoded",
          Accept: "application/json" },
        body: form.toString(),
        signal: timeout,
      });
    } catch (e) {
      throw new ConnectionError("Requesting an access token failed", { cause: e });
    }
    if (!response.ok) {
      const body = (await parseJson(response).catch(() => undefined)) as Record<string, unknown> | undefined;
      const code = typeof body?.["error"] === "string" ? body["error"] : `HTTP_${response.status}`;
      const detail = typeof body?.["error_description"] === "string" ? body["error_description"] : undefined;
      throw new LedgerForgeError({ status: response.status, code, detail,
        message: `Could not get an access token: ${code}${detail ? ` (${detail})` : ""}` });
    }
    const issued = (await parseJson(response)) as AccessToken;
    this.token = issued.access_token;
    // Refresh early, so a token never expires between our check and the server's.
    this.expiresAt = Date.now() + Math.max(0, issued.expires_in - 60) * 1000;
    return issued.access_token;
  }
}

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text();
  return text ? JSON.parse(text) : undefined;
}

async function toError(response: Response): Promise<LedgerForgeError> {
  let problem: Record<string, unknown> = {};
  try {
    const parsed = await parseJson(response);
    if (parsed && typeof parsed === "object") problem = parsed as Record<string, unknown>;
  } catch {
    // not JSON: keep the status
  }
  const code = typeof problem["code"] === "string" ? problem["code"] : `HTTP_${response.status}`;
  const detail = typeof problem["detail"] === "string" ? problem["detail"] : undefined;
  const requestId = typeof problem["requestId"] === "string" ? problem["requestId"]
    : response.headers.get("X-Request-Id") ?? undefined;
  const errors = Array.isArray(problem["errors"]) ? (problem["errors"] as FieldError[]) : undefined;
  const init = { status: response.status, code, detail, requestId, errors,
    message: `${response.status} ${code}${detail ? `: ${detail}` : ""}` };
  if (response.status === 429) {
    const after = Number(response.headers.get("Retry-After"));
    return new RateLimitError({ ...init, retryAfterSeconds: Number.isFinite(after) ? after : undefined });
  }
  return new LedgerForgeError(init);
}

function retryAfterMs(response: Response): number | undefined {
  const header = response.headers.get("Retry-After");
  if (!header) return undefined;
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds >= 0 ? Math.min(seconds, MAX_RETRY_AFTER_SECONDS) * 1000 : undefined;
}

/** Exponential backoff with full jitter: ~0.5 s, 1 s, 2 s... capped at 8 s. */
function backoff(attempt: number): number {
  return Math.random() * Math.min(8000, 500 * 2 ** attempt);
}

function raceAbort<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) return Promise.reject(signal.reason);
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason);
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
  });
}

function isAbort(signal: AbortSignal | undefined): boolean {
  return signal?.aborted === true;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(() => { signal?.removeEventListener("abort", onAbort); resolve(); }, ms);
    const onAbort = () => { clearTimeout(timer); reject(signal!.reason); };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

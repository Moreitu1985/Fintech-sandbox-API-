/** A field-level validation problem reported by the API. */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * The API answered with an error. LedgerForge errors are RFC 9457 problem documents with a stable,
 * machine-readable `code` (e.g. `INSUFFICIENT_FUNDS`) and the `requestId` to quote to support.
 */
export class LedgerForgeError extends Error {
  override readonly name: string = "LedgerForgeError";
  /** HTTP status. */
  readonly status: number;
  /** Stable error code, e.g. INSUFFICIENT_FUNDS, IDEMPOTENCY_KEY_REUSED, NOT_FOUND. */
  readonly code: string;
  /** Human-readable explanation. */
  readonly detail: string | undefined;
  /** Correlates with the server's logs and the X-Request-Id header. */
  readonly requestId: string | undefined;
  /** Per-field problems for validation errors. */
  readonly errors: FieldError[];

  constructor(init: { status: number; code: string; message: string; detail?: string | undefined;
    requestId?: string | undefined; errors?: FieldError[] | undefined }) {
    super(init.message);
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.requestId = init.requestId;
    this.errors = init.errors ?? [];
  }
}

/** HTTP 429 after the SDK's own retries ran out. */
export class RateLimitError extends LedgerForgeError {
  override readonly name: string = "RateLimitError";
  /** Seconds the server asked us to wait. */
  readonly retryAfterSeconds: number | undefined;

  constructor(init: ConstructorParameters<typeof LedgerForgeError>[0] & { retryAfterSeconds?: number | undefined }) {
    super(init);
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}

/** No HTTP answer: the network failed or the request timed out. */
export class ConnectionError extends Error {
  override readonly name: string = "ConnectionError";

  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
  }
}

/** A webhook failed verification: don't trust or process it. */
export class WebhookVerificationError extends Error {
  override readonly name: string = "WebhookVerificationError";
}

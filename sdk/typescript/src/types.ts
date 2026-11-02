/**
 * Request and response shapes of the LedgerForge API.
 *
 * Amounts are integers in minor units (cents): R100.00 is `10000`. Ids are UUID strings and timestamps
 * are ISO 8601 strings in UTC.
 */

export type Currency = string;

export interface Account {
  id: string;
  name: string;
  currency: Currency;
  /** Funding and settlement accounts may go negative; customer accounts never can. */
  allowNegative: boolean;
  /** Current balance in minor units. */
  balance: number;
  createdAt: string;
}

export interface CreateAccountParams {
  name: string;
  /** 3-letter ISO code, e.g. "ZAR". */
  currency: Currency;
  allowNegative?: boolean;
}

export interface Entry {
  transactionId: string;
  /** Positive for money in, negative for money out. */
  amount: number;
  createdAt: string;
}

export interface Transfer {
  id: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  currency: Currency;
  reference: string | null;
  description: string | null;
  status: "POSTED";
  createdAt: string;
}

export interface CreateTransferParams {
  fromAccountId: string;
  toAccountId: string;
  /** Minor units, at least 1. */
  amount: number;
  currency: Currency;
  /** Your own reference: letters, digits and . _ : - (up to 64). */
  reference?: string;
  description?: string;
}

export interface TransferResult extends Transfer {
  /** True when this call was a retry and the original transfer was returned instead of moving money again. */
  replayed: boolean;
  /** The idempotency key that was sent (generated if you didn't pass one): reuse it to retry safely. */
  idempotencyKey: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface PageParams {
  /** Zero-based. */
  page?: number;
  /** 1-100, default 20. */
  size?: number;
}

export interface IntegrityReport {
  healthy: boolean;
  postingsSum: number;
  accountsChecked: number;
  balanceMismatches: string[];
  auditChainValid: boolean;
  auditEventsChecked: number;
  auditFirstBrokenIndex: number | null;
  checkedAt: string;
}

export interface Plugin {
  name: string;
  description: string;
}

export interface AssistantAnswer {
  answer: string;
  toolsUsed: string[];
  rounds: number;
  /** False when the answer was cut short (a limit was reached); treat it as partial. */
  complete: boolean;
  model: string;
  inputTokens: number;
  outputTokens: number;
}

// ---------- Webhooks ----------

export type WebhookEventType = "transfer.posted" | "account.created";

export interface WebhookEndpoint {
  id: string;
  url: string;
  eventTypes: WebhookEventType[];
  description: string | null;
  enabled: boolean;
  failedDeliveriesInRow: number;
  createdAt: string;
  disabledAt: string | null;
}

export interface CreateWebhookParams {
  /** HTTPS URL on port 443 that resolves to public addresses. */
  url: string;
  eventTypes: WebhookEventType[];
  description?: string;
}

/** Returned once, when an endpoint is created or its secret rotated. Store the secret safely. */
export interface WebhookEndpointWithSecret {
  endpoint: WebhookEndpoint;
  /** `whsec_...`: pass it to {@link verifyWebhook}. */
  secret: string;
}

export type WebhookDeliveryStatus = "PENDING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface WebhookDelivery {
  id: string;
  endpointId: string;
  eventId: string;
  status: WebhookDeliveryStatus;
  attempts: number;
  nextAttemptAt: string | null;
  lastAttemptAt: string | null;
  lastStatusCode: number | null;
  lastError: string | null;
  createdAt: string;
}

interface EventBase<T extends string, D> {
  /** Also the `webhook-id` header: the same on every retry, so use it to drop duplicates. */
  id: string;
  type: T;
  createdAt: string;
  data: D;
}

export type TransferPostedEvent = EventBase<"transfer.posted", Transfer>;
export type AccountCreatedEvent = EventBase<"account.created", Account>;
export type WebhookTestEvent = EventBase<"webhook.test", { message: string; endpointId: string }>;

/** Every event LedgerForge sends. Switch on `type` to narrow `data`. */
export type WebhookEvent = TransferPostedEvent | AccountCreatedEvent | WebhookTestEvent;

// ---------- OAuth ----------

export type Scope = "ledger:read" | "ledger:write" | "assistant";

export interface AccessToken {
  access_token: string;
  token_type: "Bearer";
  expires_in: number;
  scope: string;
}

import { HttpClient, type LedgerForgeOptions, type RequestOptions } from "./http.ts";
import type {
  Account, AssistantAnswer, CreateAccountParams, CreateTransferParams, CreateWebhookParams, Entry, IntegrityReport,
  Page, PageParams, Plugin, Transfer, TransferResult, WebhookDelivery, WebhookEndpoint, WebhookEndpointWithSecret,
} from "./types.ts";

const enc = encodeURIComponent;

/**
 * The LedgerForge API client.
 *
 * ```ts
 * const lf = new LedgerForge({ baseUrl: "https://api.example.com", apiKey: process.env.LEDGERFORGE_API_KEY });
 * const transfer = await lf.transfers.create({ fromAccountId, toAccountId, amount: 10_000, currency: "ZAR" });
 * ```
 *
 * Keep API keys and client secrets on your server: never ship them to a browser or mobile app.
 */
export class LedgerForge {
  readonly accounts: Accounts;
  readonly transfers: Transfers;
  readonly ledger: Ledger;
  readonly webhooks: Webhooks;
  readonly assistant: Assistant;
  readonly plugins: Plugins;

  constructor(options: LedgerForgeOptions) {
    const http = new HttpClient(options);
    this.accounts = new Accounts(http);
    this.transfers = new Transfers(http);
    this.ledger = new Ledger(http);
    this.webhooks = new Webhooks(http);
    this.assistant = new Assistant(http);
    this.plugins = new Plugins(http);
  }
}

class Resource {
  protected readonly http: HttpClient;

  constructor(http: HttpClient) {
    this.http = http;
  }

  protected async httpGet<T>(path: string, query?: Record<string, string | number | undefined>,
                         options?: RequestOptions): Promise<T> {
    return (await this.http.request<T>({ method: "GET", path, ...(query ? { query } : {}), retrySafe: true, options }))
      .data;
  }

  protected async httpPost<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return (await this.http.request<T>({ method: "POST", path, body, retrySafe: false, options })).data;
  }
}

function pageQuery(params: PageParams = {}): Record<string, number | undefined> {
  return { page: params.page, size: params.size };
}

/** Walks every page of a paged list. */
async function* paginate<T>(fetchPage: (page: number) => Promise<Page<T>>): AsyncGenerator<T> {
  for (let page = 0; ; page++) {
    const result = await fetchPage(page);
    yield* result.items;
    if (page + 1 >= result.totalPages || result.items.length === 0) return;
  }
}

export class Accounts extends Resource {
  create(params: CreateAccountParams, options?: RequestOptions): Promise<Account> {
    return this.httpPost("/v1/accounts", params, options);
  }

  retrieve(accountId: string, options?: RequestOptions): Promise<Account> {
    return this.httpGet(`/v1/accounts/${enc(accountId)}`, undefined, options);
  }

  list(params?: PageParams, options?: RequestOptions): Promise<Page<Account>> {
    return this.httpGet("/v1/accounts", pageQuery(params), options);
  }

  /** Every account, fetching pages as you iterate: `for await (const a of lf.accounts.listAll()) ...` */
  listAll(options?: RequestOptions & { pageSize?: number }): AsyncGenerator<Account> {
    return paginate((page) => this.list({ page, size: options?.pageSize ?? 100 }, options));
  }

  /** Statement, newest first. */
  entries(accountId: string, params?: PageParams, options?: RequestOptions): Promise<Page<Entry>> {
    return this.httpGet(`/v1/accounts/${enc(accountId)}/entries`, pageQuery(params), options);
  }
}

export class Transfers extends Resource {
  /**
   * Moves money. Safe to retry: the SDK sends an `Idempotency-Key` (yours, or a generated one) and retries
   * network errors and 5xx/429 with the same key, so money moves at most once. To retry yourself later
   * (after a crash, say), pass the same key again: you get the original transfer back with `replayed: true`.
   */
  async create(params: CreateTransferParams, options?: RequestOptions & { idempotencyKey?: string })
    : Promise<TransferResult> {
    const idempotencyKey = options?.idempotencyKey ?? globalThis.crypto.randomUUID();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(idempotencyKey)) {
      throw new TypeError("idempotencyKey must be 8-64 letters, digits, '-' or '_'");
    }
    const result = await this.http.request<Transfer>({
      method: "POST", path: "/v1/transfers", body: params, headers: { "Idempotency-Key": idempotencyKey },
      retrySafe: true, options,
    });
    return { ...result.data, replayed: result.headers.get("Idempotent-Replayed") === "true", idempotencyKey };
  }

  retrieve(transferId: string, options?: RequestOptions): Promise<Transfer> {
    return this.httpGet(`/v1/transfers/${enc(transferId)}`, undefined, options);
  }
}

export class Ledger extends Resource {
  /** Proof that the books balance and the audit chain is intact. */
  integrity(options?: RequestOptions): Promise<IntegrityReport> {
    return this.httpGet("/v1/ledger/integrity", undefined, options);
  }
}

export class Webhooks extends Resource {
  /** Registers an endpoint. The returned `secret` is shown only now: store it to verify deliveries. */
  create(params: CreateWebhookParams, options?: RequestOptions): Promise<WebhookEndpointWithSecret> {
    return this.httpPost("/v1/webhooks", params, options);
  }

  list(options?: RequestOptions): Promise<WebhookEndpoint[]> {
    return this.httpGet("/v1/webhooks", undefined, options);
  }

  retrieve(endpointId: string, options?: RequestOptions): Promise<WebhookEndpoint> {
    return this.httpGet(`/v1/webhooks/${enc(endpointId)}`, undefined, options);
  }

  async delete(endpointId: string, options?: RequestOptions): Promise<void> {
    // Not retried: if a first attempt went through, a retry would answer 404 and hide the success.
    await this.http.request<void>({ method: "DELETE", path: `/v1/webhooks/${enc(endpointId)}`, retrySafe: false,
      options });
  }

  /** The old secret stops working immediately. */
  rotateSecret(endpointId: string, options?: RequestOptions): Promise<WebhookEndpointWithSecret> {
    return this.httpPost(`/v1/webhooks/${enc(endpointId)}/rotate-secret`, undefined, options);
  }

  enable(endpointId: string, options?: RequestOptions): Promise<WebhookEndpoint> {
    return this.httpPost(`/v1/webhooks/${enc(endpointId)}/enable`, undefined, options);
  }

  /** Sends a `webhook.test` event to this endpoint. */
  sendTest(endpointId: string, options?: RequestOptions): Promise<{ eventId: string }> {
    return this.httpPost(`/v1/webhooks/${enc(endpointId)}/test`, undefined, options);
  }

  deliveries(endpointId: string, params?: PageParams, options?: RequestOptions): Promise<Page<WebhookDelivery>> {
    return this.httpGet(`/v1/webhooks/${enc(endpointId)}/deliveries`, pageQuery(params), options);
  }

  retryDelivery(deliveryId: string, options?: RequestOptions): Promise<WebhookDelivery> {
    return this.httpPost(`/v1/webhooks/deliveries/${enc(deliveryId)}/retry`, undefined, options);
  }
}

export class Assistant extends Resource {
  /** Plain-English questions about your own ledger, e.g. "Which account had the most outflows this week?" */
  ask(question: string, options?: RequestOptions): Promise<AssistantAnswer> {
    return this.httpPost("/v1/assistant/ask", { question }, options);
  }
}

export class Plugins extends Resource {
  list(options?: RequestOptions): Promise<Plugin[]> {
    return this.httpGet("/v1/plugins", undefined, options);
  }
}

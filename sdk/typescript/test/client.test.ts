import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import { ConnectionError, LedgerForge, LedgerForgeError, RateLimitError } from "../src/index.ts";
import { FakeFetch, json, problem } from "./fake-fetch.ts";

const transfer = { id: "t1", fromAccountId: "a", toAccountId: "b", amount: 100, currency: "ZAR", reference: null,
  description: null, status: "POSTED", createdAt: "2026-11-02T10:00:00Z" };
const account = { id: "a1", name: "Alice", currency: "ZAR", allowNegative: false, balance: 0,
  createdAt: "2026-11-02T10:00:00Z" };

function client(fake: FakeFetch, extra: Record<string, unknown> = {}) {
  return new LedgerForge({ baseUrl: "https://api.test/", apiKey: "lf_test_key", fetch: fake.fetch, maxRetries: 2,
    ...extra });
}

describe("configuration", () => {
  it("needs exactly one way to authenticate", () => {
    assert.throws(() => new LedgerForge({}), TypeError);
    assert.throws(() => new LedgerForge({ apiKey: "k", clientCredentials: { clientId: "i", clientSecret: "s" } }),
      TypeError);
  });

  it("sends the API key as a bearer token and builds URLs and queries", async () => {
    const fake = new FakeFetch().reply(json(200, { items: [], page: 2, size: 5, totalItems: 0, totalPages: 0 }));
    await client(fake).accounts.list({ page: 2, size: 5 });
    const [req] = fake.requests;
    assert.equal(req!.url.toString(), "https://api.test/v1/accounts?page=2&size=5");
    assert.equal(req!.headers.get("authorization"), "Bearer lf_test_key");
    assert.equal(req!.headers.get("accept"), "application/json");
  });

  it("escapes ids in paths", async () => {
    const fake = new FakeFetch().reply(json(200, account));
    await client(fake).accounts.retrieve("../admin");
    assert.equal(fake.requests[0]!.url.pathname, "/v1/accounts/..%2Fadmin");
  });
});

describe("transfers", () => {
  it("generates an idempotency key and reports it", async () => {
    const fake = new FakeFetch().reply(json(201, transfer));
    const result = await client(fake).transfers.create({ fromAccountId: "a", toAccountId: "b", amount: 100,
      currency: "ZAR" });
    const sent = fake.requests[0]!.headers.get("idempotency-key");
    assert.match(sent!, /^[0-9a-f-]{36}$/);
    assert.equal(result.idempotencyKey, sent);
    assert.equal(result.replayed, false);
    assert.deepEqual(JSON.parse(fake.requests[0]!.body!), { fromAccountId: "a", toAccountId: "b", amount: 100,
      currency: "ZAR" });
  });

  it("retries with the same key, so money moves at most once", async () => {
    const fake = new FakeFetch().reply(new TypeError("socket hang up"),
      problem(503, "FRAUD_CHECK_UNAVAILABLE", "try later"),
      json(200, transfer, { "Idempotent-Replayed": "true" }));
    const result = await client(fake).transfers.create({ fromAccountId: "a", toAccountId: "b", amount: 100,
      currency: "ZAR" }, { idempotencyKey: "order-42-payout" });
    assert.equal(fake.requests.length, 3);
    assert.deepEqual(fake.requests.map((r) => r.headers.get("idempotency-key")),
      ["order-42-payout", "order-42-payout", "order-42-payout"]);
    assert.equal(result.replayed, true);
  });

  it("rejects malformed idempotency keys before sending", async () => {
    const fake = new FakeFetch();
    await assert.rejects(client(fake).transfers.create({ fromAccountId: "a", toAccountId: "b", amount: 1,
      currency: "ZAR" }, { idempotencyKey: "bad key!" }), TypeError);
    assert.equal(fake.requests.length, 0);
  });

  it("surfaces business errors with their code, detail and request id", async () => {
    const fake = new FakeFetch().reply(problem(422, "INSUFFICIENT_FUNDS", "The source account has insufficient funds."));
    const err = await client(fake).transfers.create({ fromAccountId: "a", toAccountId: "b", amount: 1,
      currency: "ZAR" }).catch((e: unknown) => e);
    assert.ok(err instanceof LedgerForgeError);
    assert.equal(err.status, 422);
    assert.equal(err.code, "INSUFFICIENT_FUNDS");
    assert.equal(err.detail, "The source account has insufficient funds.");
    assert.equal(err.requestId, "req-123");
    assert.equal(fake.requests.length, 1, "4xx is not retried");
  });
});

describe("retries", () => {
  it("retries reads on 502/503/504 and honours Retry-After on 429", async () => {
    const fake = new FakeFetch().reply(problem(429, "RATE_LIMITED", "slow down", {}, { "Retry-After": "0" }),
      new Response("bad gateway", { status: 502 }), json(200, account));
    const got = await client(fake).accounts.retrieve("a1");
    assert.equal(got.name, "Alice");
    assert.equal(fake.requests.length, 3);
  });

  it("never retries writes that have no idempotency key", async () => {
    const fake = new FakeFetch().reply(problem(503, "UNAVAILABLE", "down"));
    await assert.rejects(client(fake).accounts.create({ name: "Bob", currency: "ZAR" }),
      (e: unknown) => e instanceof LedgerForgeError && e.status === 503);
    assert.equal(fake.requests.length, 1);
  });

  it("stops after maxRetries and reports rate limiting with its wait time", async () => {
    const limited = () => problem(429, "RATE_LIMITED", "slow down", {}, { "Retry-After": "0" });
    const fake = new FakeFetch().reply(limited(), limited(), limited());
    const err = await client(fake).accounts.retrieve("a1").catch((e: unknown) => e);
    assert.ok(err instanceof RateLimitError);
    assert.equal(err.retryAfterSeconds, 0);
    assert.equal(fake.requests.length, 3);
  });

  it("turns network failures and timeouts into ConnectionError", async () => {
    const fake = new FakeFetch().reply(new TypeError("ECONNREFUSED"));
    await assert.rejects(client(fake, { maxRetries: 0 }).accounts.retrieve("a1"), ConnectionError);

    const slow = new FakeFetch().reply((req) => new Promise<Response>(() => { void req; }));
    const hang: typeof fetch = (input, init) => new Promise((_, reject) => {
      init?.signal?.addEventListener("abort", () => reject(init.signal!.reason));
      void slow.fetch(input, init);
    });
    // A real socket keeps Node's event loop alive while waiting; this fake doesn't, so hold it open.
    const keepAlive = setInterval(() => {}, 1000);
    try {
      await assert.rejects(new LedgerForge({ apiKey: "k", fetch: hang, maxRetries: 0, timeoutMs: 20 })
        .accounts.retrieve("a1"), /timed out/);
    } finally {
      clearInterval(keepAlive);
    }
  });

  it("stops at once when the caller aborts", async () => {
    const fake = new FakeFetch().reply(new TypeError("boom"));
    const controller = new AbortController();
    controller.abort(new Error("user cancelled"));
    await assert.rejects(client(fake).accounts.retrieve("a1", { signal: controller.signal }), /user cancelled/);
    assert.ok(fake.requests.length <= 1);
  });
});

describe("pagination", () => {
  it("walks every page", async () => {
    const page = (n: number, items: unknown[]) => json(200, { items, page: n, size: 2, totalItems: 3, totalPages: 2 });
    const fake = new FakeFetch().reply(page(0, [account, { ...account, id: "a2" }]), page(1, [{ ...account, id: "a3" }]));
    const ids: string[] = [];
    for await (const a of client(fake).accounts.listAll({ pageSize: 2 })) ids.push(a.id);
    assert.deepEqual(ids, ["a1", "a2", "a3"]);
    assert.deepEqual(fake.requests.map((r) => r.url.searchParams.get("page")), ["0", "1"]);
  });
});

describe("OAuth client credentials", () => {
  const creds = { clientId: "key-id", clientSecret: "lf_test_secret", scopes: ["ledger:read" as const] };
  const token = (value: string, expiresIn = 900) =>
    json(200, { access_token: value, token_type: "Bearer", expires_in: expiresIn, scope: "ledger:read" });

  it("fetches one token, caches it and uses it as the bearer", async () => {
    const fake = new FakeFetch().reply(token("tok-1"), json(200, account), json(200, account));
    const lf = new LedgerForge({ baseUrl: "https://api.test", clientCredentials: creds, fetch: fake.fetch });
    await lf.accounts.retrieve("a1");
    await lf.accounts.retrieve("a1");
    const [tokenReq, first, second] = fake.requests;
    assert.equal(tokenReq!.url.pathname, "/oauth/token");
    assert.equal(tokenReq!.headers.get("authorization"), "Basic " + btoa("key-id:lf_test_secret"));
    const form = new URLSearchParams(tokenReq!.body);
    assert.equal(form.get("grant_type"), "client_credentials");
    assert.equal(form.get("scope"), "ledger:read");
    assert.equal(first!.headers.get("authorization"), "Bearer tok-1");
    assert.equal(second!.headers.get("authorization"), "Bearer tok-1");
    assert.ok(!first!.url.search.includes("secret") && !first!.headers.get("authorization")!.includes("secret"),
      "the client secret only ever goes to the token endpoint");
  });

  it("shares one token request between concurrent calls", async () => {
    const fake = new FakeFetch().reply(token("tok-1"), json(200, account), json(200, account), json(200, account));
    const lf = new LedgerForge({ clientCredentials: creds, fetch: fake.fetch });
    await Promise.all([lf.accounts.retrieve("a"), lf.accounts.retrieve("b"), lf.accounts.retrieve("c")]);
    assert.equal(fake.requests.filter((r) => r.url.pathname === "/oauth/token").length, 1);
  });

  it("one caller cancelling doesn't fail the shared token refresh for the others", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const fake = new FakeFetch().reply(async () => { await gate; return token("tok-1"); }, json(200, account));
    const lf = new LedgerForge({ clientCredentials: creds, fetch: fake.fetch });
    const controller = new AbortController();
    const cancelled = lf.accounts.retrieve("a", { signal: controller.signal });
    const other = lf.accounts.retrieve("b");
    controller.abort(new Error("cancelled"));
    release();
    await assert.rejects(cancelled, /cancelled/);
    assert.equal((await other).id, "a1");
  });

  it("gets a new token once when the API rejects the current one", async () => {
    const fake = new FakeFetch().reply(token("old"), problem(401, "UNAUTHORIZED", "expired"), token("new"),
      json(200, account));
    const lf = new LedgerForge({ clientCredentials: creds, fetch: fake.fetch });
    await lf.accounts.retrieve("a1");
    assert.equal(fake.requests[3]!.headers.get("authorization"), "Bearer new");
  });

  it("refreshes a token that is about to expire", async () => {
    const fake = new FakeFetch().reply(token("short", 30), json(200, account), token("fresh"), json(200, account));
    const lf = new LedgerForge({ clientCredentials: creds, fetch: fake.fetch });
    await lf.accounts.retrieve("a1");
    await lf.accounts.retrieve("a1");
    assert.equal(fake.requests[3]!.headers.get("authorization"), "Bearer fresh");
  });

  it("reports token errors in OAuth terms", async () => {
    const fake = new FakeFetch().reply(json(401, { error: "invalid_client", error_description: "bad credentials" }));
    const lf = new LedgerForge({ clientCredentials: creds, fetch: fake.fetch });
    await assert.rejects(lf.accounts.retrieve("a1"),
      (e: unknown) => e instanceof LedgerForgeError && e.code === "invalid_client");
  });
});

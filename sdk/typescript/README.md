# LedgerForge TypeScript SDK

Typed client for the [LedgerForge](../../README.md) ledger API, with webhook signature verification.

- **No runtime dependencies.** Uses the platform's `fetch` and Web Crypto: Node 20+, Deno, Bun, edge runtimes.
- **Safe retries.** Reads and transfers are retried on network errors, 429 (honouring `Retry-After`) and 502/503/504. Transfers always carry an `Idempotency-Key`, so a retry can never move money twice.
- **OAuth 2.0 built in.** Give it client credentials and it fetches, caches and refreshes 15-minute access tokens, with least-privilege scopes.
- **Webhooks.** `verifyWebhook` checks the Standard Webhooks signature, rejects replays and returns a typed event.
- **Typed errors.** `LedgerForgeError` carries the API's stable `code` (e.g. `INSUFFICIENT_FUNDS`), `detail` and `requestId`.
- ESM and CommonJS builds with type declarations.

> Keep API keys and client secrets on your server. Never ship them in a browser or mobile app.

## Install

Not yet published to npm. Build it from this folder:

```bash
cd sdk/typescript && npm ci && npm run build && npm pack   # produces ledgerforge-sdk-0.4.0.tgz
npm install ./ledgerforge-sdk-0.4.0.tgz                      # in your project
```

## Quickstart

```ts
import { LedgerForge, LedgerForgeError } from "@ledgerforge/sdk";

const lf = new LedgerForge({ baseUrl: "https://api.example.com", apiKey: process.env.LEDGERFORGE_API_KEY! });

const treasury = await lf.accounts.create({ name: "Treasury", currency: "ZAR", allowNegative: true });
const alice = await lf.accounts.create({ name: "Alice", currency: "ZAR" });

// Amounts are integers in cents: R250.00
const t = await lf.transfers.create({ fromAccountId: treasury.id, toAccountId: alice.id, amount: 25_000, currency: "ZAR" });

try {
  await lf.transfers.create({ fromAccountId: alice.id, toAccountId: treasury.id, amount: 1_000_000, currency: "ZAR" });
} catch (e) {
  if (e instanceof LedgerForgeError && e.code === "INSUFFICIENT_FUNDS") { /* tell the customer */ }
}

for await (const account of lf.accounts.listAll()) console.log(account.name, account.balance);
console.log((await lf.ledger.integrity()).healthy); // the books balance
```

### Retrying a transfer yourself

The SDK retries within one call. If your process crashes mid-payment, retry later with the **same** key: you get the original transfer back (`replayed: true`) and no money moves twice.

```ts
const key = `payout-${order.id}`;            // stable per logical payment
const result = await lf.transfers.create(params, { idempotencyKey: key });
result.replayed;                              // true if it had already happened
```

### OAuth client credentials

```ts
const reporting = new LedgerForge({
  baseUrl,
  clientCredentials: { clientId: keyId, clientSecret: apiKey, scopes: ["ledger:read"] },
});
```

The secret is sent only to `/oauth/token`. Ordinary requests carry a short-lived token, refreshed before it expires (and once more if the API rejects it). A read-only token like this one can't move money.

## Webhooks

```ts
const { endpoint, secret } = await lf.webhooks.create({
  url: "https://example.com/webhooks/ledgerforge",
  eventTypes: ["transfer.posted", "account.created"],
});
// Store `secret` (whsec_...). It is shown only now; rotate with lf.webhooks.rotateSecret(endpoint.id).
```

Verify every delivery against the **raw** body (re-serialised JSON won't match), and de-duplicate on `event.id`: delivery is at-least-once.

```ts
import express from "express";
import { verifyWebhook, WebhookVerificationError } from "@ledgerforge/sdk";

app.post("/webhooks/ledgerforge", express.raw({ type: "application/json" }), async (req, res) => {
  try {
    const event = await verifyWebhook(req.body, req.headers, process.env.LEDGERFORGE_WEBHOOK_SECRET!);
    if (await alreadyHandled(event.id)) return res.sendStatus(200);
    if (event.type === "transfer.posted") await creditCustomer(event.data.toAccountId, event.data.amount);
    res.sendStatus(200);
  } catch (e) {
    res.sendStatus(e instanceof WebhookVerificationError ? 400 : 500);
  }
});
```

`verifyWebhook` rejects a missing or wrong signature, an altered body, a payload whose id doesn't match its header, and timestamps more than 5 minutes off (replays). A framework-free receiver is in [`examples/webhook-receiver.ts`](examples/webhook-receiver.ts). `signWebhook` produces valid signatures for testing your own receiver.

## API

| | |
|---|---|
| `accounts` | `create`, `retrieve`, `list`, `listAll`, `entries` |
| `transfers` | `create` (idempotent), `retrieve` |
| `ledger` | `integrity` |
| `webhooks` | `create`, `list`, `retrieve`, `delete`, `rotateSecret`, `enable`, `sendTest`, `deliveries`, `retryDelivery` |
| `assistant` | `ask` |
| `plugins` | `list` |

Every method takes optional `{ signal, timeoutMs }`. Client options: `baseUrl`, `apiKey` or `clientCredentials`, `timeoutMs` (30 s), `maxRetries` (2), `fetch`.

## Development

```bash
npm ci
npm run typecheck && npm test     # unit tests (Node 22+), fake fetch, no server needed
npm run build && npm run test:dist
BASE_URL=http://localhost:8080 ADMIN_TOKEN=... npm run e2e   # against a running API (dev profile)
```

CI runs the unit tests on Node 22 and 24, checks the built package on Node 20, 22 and 24, and runs the end-to-end test against the real API, including receiving and verifying real webhooks.

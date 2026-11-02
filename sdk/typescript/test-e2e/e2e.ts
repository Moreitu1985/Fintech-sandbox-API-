// End to end against a running LedgerForge (CI starts one). Needs BASE_URL and ADMIN_TOKEN.
// The API must allow local webhook receivers (the dev profile does), because the receiver runs here.
import { strict as assert } from "node:assert";
import { createServer, type IncomingHttpHeaders } from "node:http";
import type { AddressInfo } from "node:net";
import { LedgerForge, LedgerForgeError, verifyWebhook, type WebhookEvent } from "../src/index.ts";

const baseUrl = process.env["BASE_URL"] ?? "http://localhost:8080";
const adminToken = process.env["ADMIN_TOKEN"];
if (!adminToken) throw new Error("ADMIN_TOKEN is required");

function step(name: string) {
  console.log(`✓ ${name}`);
}

// ---- A tenant, created through the admin API (not part of the tenant SDK). ----
const created = await fetch(`${baseUrl}/admin/tenants`, {
  method: "POST",
  headers: { "X-Admin-Token": adminToken, "Content-Type": "application/json" },
  body: JSON.stringify({ name: `SDK e2e ${Date.now()}` }),
});
assert.equal(created.status, 201, "create tenant");
const tenant = (await created.json()) as { apiKey: { keyId: string; apiKey: string } };

// ---- A local webhook receiver that keeps the raw bodies. ----
const received: { headers: IncomingHttpHeaders; body: Buffer }[] = [];
const server = createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  received.push({ headers: req.headers, body: Buffer.concat(chunks) });
  res.writeHead(200).end();
});
await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const receiverUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}/hooks`;

try {
  // ---- OAuth client credentials: the SDK gets and caches tokens itself. ----
  const lf = new LedgerForge({
    baseUrl,
    clientCredentials: { clientId: tenant.apiKey.keyId, clientSecret: tenant.apiKey.apiKey,
      scopes: ["ledger:read", "ledger:write"] },
  });

  const hook = await lf.webhooks.create({ url: receiverUrl, eventTypes: ["transfer.posted", "account.created"],
    description: "SDK e2e" });
  assert.match(hook.secret, /^whsec_/);
  step("webhook endpoint registered");

  const treasury = await lf.accounts.create({ name: "Treasury", currency: "ZAR", allowNegative: true });
  const alice = await lf.accounts.create({ name: "Alice", currency: "ZAR" });
  const bob = await lf.accounts.create({ name: "Bob", currency: "ZAR" });
  step("accounts opened");

  await lf.transfers.create({ fromAccountId: treasury.id, toAccountId: alice.id, amount: 100_000, currency: "ZAR",
    reference: "funding" });
  const pay = await lf.transfers.create({ fromAccountId: alice.id, toAccountId: bob.id, amount: 2_500,
    currency: "ZAR", description: "Lunch" });
  assert.equal(pay.replayed, false);
  const retry = await lf.transfers.create({ fromAccountId: alice.id, toAccountId: bob.id, amount: 2_500,
    currency: "ZAR", description: "Lunch" }, { idempotencyKey: pay.idempotencyKey });
  assert.equal(retry.replayed, true);
  assert.equal(retry.id, pay.id);
  step("transfers posted; a retry with the same key moved no money");

  const declined = await lf.transfers.create({ fromAccountId: bob.id, toAccountId: alice.id, amount: 50_000,
    currency: "ZAR" }).catch((e: unknown) => e);
  assert.ok(declined instanceof LedgerForgeError && declined.code === "INSUFFICIENT_FUNDS", String(declined));
  assert.ok(declined.requestId, "errors carry a request id");
  step("insufficient funds surfaced as a typed error");

  assert.equal((await lf.accounts.retrieve(alice.id)).balance, 97_500);
  assert.equal((await lf.accounts.retrieve(bob.id)).balance, 2_500);
  const statement = await lf.accounts.entries(alice.id);
  assert.deepEqual(statement.items.map((e) => e.amount), [-2_500, 100_000]);
  let count = 0;
  for await (const _ of lf.accounts.listAll({ pageSize: 2 })) count++;
  assert.equal(count, 3);
  const integrity = await lf.ledger.integrity();
  assert.ok(integrity.healthy && integrity.auditChainValid && integrity.postingsSum === 0);
  step("balances, statement, pagination and integrity check out");

  // ---- Webhooks: 3 account.created + 2 transfer.posted, each verified with the SDK. ----
  const deadline = Date.now() + 20_000;
  while (received.length < 5 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 100));
  assert.equal(received.length, 5, `expected 5 webhooks, got ${received.length}`);
  const events: WebhookEvent[] = [];
  for (const r of received) events.push(await verifyWebhook(r.body, r.headers, hook.secret));
  assert.equal(new Set(events.map((e) => e.id)).size, 5, "every event has its own id");
  const transfers = events.flatMap((e) => (e.type === "transfer.posted" ? [e.data] : []));
  assert.deepEqual(transfers.map((t) => t.amount).sort((a, b) => a - b), [2_500, 100_000]);
  assert.equal(events.filter((e) => e.type === "account.created").length, 3);
  // Any change to the signed bytes must fail, even one that leaves the JSON meaning the same.
  const tampered = Buffer.concat([received[0]!.body, Buffer.from(" ")]);
  await assert.rejects(verifyWebhook(tampered, received[0]!.headers, hook.secret));
  step("5 webhooks received and verified; a tampered one is rejected");

  const history = await lf.webhooks.deliveries(hook.endpoint.id);
  assert.ok(history.items.every((d) => d.status === "SUCCEEDED"), JSON.stringify(history.items));

  const rotated = await lf.webhooks.rotateSecret(hook.endpoint.id);
  await lf.webhooks.sendTest(hook.endpoint.id);
  while (received.length < 6 && Date.now() < deadline + 10_000) await new Promise((r) => setTimeout(r, 100));
  const test = await verifyWebhook(received[5]!.body, received[5]!.headers, rotated.secret);
  assert.equal(test.type, "webhook.test");
  await assert.rejects(verifyWebhook(received[5]!.body, received[5]!.headers, hook.secret));
  step("rotated secret signs new deliveries; the old one no longer verifies");

  // ---- Least privilege: a read-only client can look but not move money. ----
  const reader = new LedgerForge({ baseUrl, clientCredentials: { clientId: tenant.apiKey.keyId,
    clientSecret: tenant.apiKey.apiKey, scopes: ["ledger:read"] } });
  assert.equal((await reader.accounts.retrieve(bob.id)).balance, 2_500);
  const forbidden = await reader.transfers.create({ fromAccountId: bob.id, toAccountId: alice.id, amount: 1,
    currency: "ZAR" }).catch((e: unknown) => e);
  assert.ok(forbidden instanceof LedgerForgeError && forbidden.status === 403, String(forbidden));
  step("read-only token can read but not transfer");

  await lf.webhooks.delete(hook.endpoint.id);
  assert.equal((await lf.webhooks.list()).length, 0);
  step("endpoint deleted");
  console.log("\nSDK end-to-end: all checks passed");
} finally {
  server.close();
}

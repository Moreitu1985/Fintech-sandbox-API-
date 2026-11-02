import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import { signWebhook, verifyWebhook, WebhookVerificationError, type WebhookEvent } from "../src/index.ts";

const secret = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
const id = "4b6f6a0e-6c8f-4c1e-9d5b-1f2e3d4c5b6a";
const ts = 1_800_000_000;
const body = JSON.stringify({ id, type: "transfer.posted", createdAt: "2027-01-15T08:00:00Z",
  data: { id: "t1", fromAccountId: "a", toAccountId: "b", amount: 12345, currency: "ZAR", reference: null,
    description: null, status: "POSTED", createdAt: "2027-01-15T08:00:00Z" } });

async function headers(payload = body, at = ts, key = secret) {
  return { "webhook-id": id, "webhook-timestamp": String(at), "webhook-signature": await signWebhook(key, id, at, payload) };
}

describe("signWebhook", () => {
  it("matches the Standard Webhooks reference test vector", async () => {
    // From the Standard Webhooks specification's reference libraries (also checked by the Java tests).
    assert.equal(await signWebhook(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330, '{"test": 2432232314}'),
      "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
  });
});

describe("verifyWebhook", () => {
  it("accepts a genuine event and narrows it by type", async () => {
    const event: WebhookEvent = await verifyWebhook(body, await headers(), secret, { now: ts + 10 });
    assert.equal(event.type, "transfer.posted");
    if (event.type === "transfer.posted") assert.equal(event.data.amount, 12345);
  });

  it("accepts fetch Headers, Node header objects and raw bytes", async () => {
    const h = await headers();
    await verifyWebhook(body, new Headers(h), secret, { now: ts });
    await verifyWebhook(new TextEncoder().encode(body), { "Webhook-Id": [id], "WEBHOOK-TIMESTAMP": String(ts),
      "webhook-signature": [h["webhook-signature"]] }, secret, { now: ts });
  });

  it("accepts any one valid signature among several (key rotation)", async () => {
    const h = await headers();
    h["webhook-signature"] = `v1,AAAA v2,whatever ${h["webhook-signature"]}`;
    await verifyWebhook(body, h, secret, { now: ts });
  });

  const rejects = (name: string, run: () => Promise<unknown>, message: RegExp) =>
    it(name, async () => {
      await assert.rejects(run(), (e: unknown) => e instanceof WebhookVerificationError && message.test(e.message));
    });

  rejects("rejects a body changed after signing", async () =>
    verifyWebhook(body.replace("12345", "99999"), await headers(), secret, { now: ts }), /No matching signature/);
  rejects("rejects the wrong secret", async () =>
    verifyWebhook(body, await headers(), "whsec_" + btoa("x".repeat(32)), { now: ts }), /No matching signature/);
  rejects("rejects an old event (replay)", async () =>
    verifyWebhook(body, await headers(), secret, { now: ts + 301 }), /tolerance/);
  rejects("rejects a timestamp from the future", async () =>
    verifyWebhook(body, await headers(), secret, { now: ts - 301 }), /tolerance/);
  rejects("rejects missing headers", async () =>
    verifyWebhook(body, { "webhook-id": id }, secret, { now: ts }), /Missing/);
  rejects("rejects a malformed timestamp", async () =>
    verifyWebhook(body, { ...(await headers()), "webhook-timestamp": "12e9" }, secret, { now: ts }), /timestamp/);
  rejects("rejects a valid signature replayed under another id", async () => {
    const h = await headers();
    const otherId = "00000000-0000-0000-0000-000000000000";
    h["webhook-id"] = otherId;
    h["webhook-signature"] = await signWebhook(secret, otherId, ts, body);
    return verifyWebhook(body, h, secret, { now: ts });
  }, /does not match/);
  rejects("rejects secrets that aren't whsec_", async () =>
    verifyWebhook(body, await headers(), "not-a-secret", { now: ts }), /whsec_/);
});

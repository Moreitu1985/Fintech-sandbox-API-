import { strict as assert } from "node:assert";
import { randomBytes } from "node:crypto";
import { describe, it } from "node:test";
import { seal, sessionKey, SESSION_TTL_SECONDS, unseal } from "../lib/session.ts";

const key = randomBytes(32);
const session = { apiKey: "lf_test_0123456789ab_" + "f".repeat(64), iat: 1_800_000_000 };

describe("session cookie", () => {
  it("round-trips and never contains the key in readable form", () => {
    const sealed = seal(session, key);
    assert.ok(!sealed.includes("lf_test_"));
    assert.ok(!Buffer.from(sealed.slice(3), "base64url").toString("latin1").includes("lf_test_"));
    assert.deepEqual(unseal(sealed, key, session.iat + 10), session);
  });

  it("uses a fresh IV every time", () => {
    assert.notEqual(seal(session, key), seal(session, key));
  });

  it("rejects altered cookies, other keys and garbage", () => {
    const sealed = seal(session, key);
    const raw = Buffer.from(sealed.slice(3), "base64url");
    raw.writeUInt8(raw.readUInt8(raw.length - 1) ^ 1, raw.length - 1);
    assert.equal(unseal("v1." + raw.toString("base64url"), key, session.iat), null);
    assert.equal(unseal(sealed, randomBytes(32), session.iat), null);
    for (const bad of [undefined, "", "v1.", "v2." + sealed.slice(3), "v1.!!!", "v1." + "A".repeat(10)]) {
      assert.equal(unseal(bad, key, session.iat), null);
    }
  });

  it("expires after the session lifetime and refuses future-dated sessions", () => {
    const sealed = seal(session, key);
    assert.ok(unseal(sealed, key, session.iat + SESSION_TTL_SECONDS));
    assert.equal(unseal(sealed, key, session.iat + SESSION_TTL_SECONDS + 1), null);
    assert.equal(unseal(sealed, key, session.iat - 3600), null);
  });

  it("requires a proper secret in production", () => {
    assert.throws(() => sessionKey({ NODE_ENV: "production" }), /required/);
    assert.throws(() => sessionKey({ DASHBOARD_SESSION_SECRET: "c2hvcnQ=" }), /32 bytes/);
    // A passphrase that happens to be 44 characters is not a key.
    assert.throws(() => sessionKey({ DASHBOARD_SESSION_SECRET: "correct horse battery staple and more words!" }), /32 bytes/);
    assert.equal(sessionKey({ DASHBOARD_SESSION_SECRET: randomBytes(32).toString("base64") }).length, 32);
    assert.equal(sessionKey({}).length, 32, "development falls back to a temporary key");
  });
});

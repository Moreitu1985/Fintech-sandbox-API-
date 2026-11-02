// Loads the built package the way users will: ESM import and CommonJS require, through package.json "exports".
import { strict as assert } from "node:assert";
import { createRequire } from "node:module";
import { existsSync } from "node:fs";

const esm = await import("@ledgerforge/sdk");
const cjs = createRequire(import.meta.url)("@ledgerforge/sdk");
for (const [label, mod] of [["esm", esm], ["cjs", cjs]]) {
  for (const name of ["LedgerForge", "LedgerForgeError", "RateLimitError", "ConnectionError",
    "WebhookVerificationError", "verifyWebhook", "signWebhook", "SDK_VERSION"]) {
    assert.ok(mod[name] !== undefined, `${label} build is missing ${name}`);
  }
  const sig = await mod.signWebhook("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", "msg_p5jXN8AQM9LWM0D4loKWxJek",
    1614265330, '{"test": 2432232314}');
  assert.equal(sig, "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=", `${label} signature`);
  assert.ok(new mod.LedgerForge({ apiKey: "k" }).transfers, `${label} client`);
}
for (const f of ["dist/esm/index.d.ts", "dist/cjs/index.d.ts", "dist/cjs/package.json"]) {
  assert.ok(existsSync(new URL("../" + f, import.meta.url)), `missing ${f}`);
}
console.log(`dist OK on Node ${process.version} (ESM + CommonJS)`);

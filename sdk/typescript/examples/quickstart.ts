// Run: LEDGERFORGE_API_KEY=lf_test_... node --experimental-strip-types examples/quickstart.ts
import { LedgerForge, LedgerForgeError } from "../src/index.ts";

const lf = new LedgerForge({
  baseUrl: process.env["LEDGERFORGE_URL"] ?? "http://localhost:8080",
  apiKey: process.env["LEDGERFORGE_API_KEY"]!,
});

const treasury = await lf.accounts.create({ name: "Treasury", currency: "ZAR", allowNegative: true });
const alice = await lf.accounts.create({ name: "Alice", currency: "ZAR" });

// R250.00, in cents. Retried automatically on network errors with the same idempotency key.
const funding = await lf.transfers.create({ fromAccountId: treasury.id, toAccountId: alice.id, amount: 25_000,
  currency: "ZAR", reference: "welcome-bonus" });
console.log("transfer", funding.id, "replayed:", funding.replayed);

// Retrying with your own key returns the original transfer: money moves once.
const again = await lf.transfers.create({ fromAccountId: treasury.id, toAccountId: alice.id, amount: 25_000,
  currency: "ZAR", reference: "welcome-bonus" }, { idempotencyKey: funding.idempotencyKey });
console.log("same transfer:", again.id === funding.id, "replayed:", again.replayed);

try {
  await lf.transfers.create({ fromAccountId: alice.id, toAccountId: treasury.id, amount: 1_000_000, currency: "ZAR" });
} catch (e) {
  if (e instanceof LedgerForgeError && e.code === "INSUFFICIENT_FUNDS") console.log("declined:", e.detail);
  else throw e;
}

console.log("Alice:", (await lf.accounts.retrieve(alice.id)).balance / 100, "ZAR");
console.log("books balanced:", (await lf.ledger.integrity()).healthy);

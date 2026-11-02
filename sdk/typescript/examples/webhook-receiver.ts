// A minimal receiver with only Node's http module. Run:
//   LEDGERFORGE_WEBHOOK_SECRET=whsec_... node --experimental-strip-types examples/webhook-receiver.ts
import { createServer } from "node:http";
import { verifyWebhook, WebhookVerificationError } from "../src/index.ts";

const secret = process.env["LEDGERFORGE_WEBHOOK_SECRET"]!;
const seen = new Set<string>(); // use your database in production

createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  try {
    // The raw bytes, exactly as received: re-serialised JSON would not match the signature.
    const event = await verifyWebhook(Buffer.concat(chunks), req.headers, secret);
    if (!seen.has(event.id)) {        // deliveries are at-least-once: drop duplicates by id
      seen.add(event.id);
      switch (event.type) {
        case "transfer.posted":
          console.log(`R${event.data.amount / 100} moved to ${event.data.toAccountId}`);
          break;
        case "account.created":
          console.log(`new account ${event.data.name}`);
          break;
        case "webhook.test":
          console.log("test event received");
          break;
      }
    }
    res.writeHead(200).end();       // answer quickly; do slow work after responding
  } catch (e) {
    res.writeHead(e instanceof WebhookVerificationError ? 400 : 500).end();
  }
}).listen(3000, () => console.log("listening on :3000"));

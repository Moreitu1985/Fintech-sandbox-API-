import { expect, test, type Page } from "@playwright/test";
import { verifyWebhook } from "@ledgerforge/sdk";
import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import type { AddressInfo } from "node:net";

const api = process.env.LEDGERFORGE_API_URL ?? "http://localhost:8080";
const adminToken = process.env.ADMIN_TOKEN ?? "";

interface TenantKey { keyId: string; apiKey: string }

async function newTenant(): Promise<TenantKey> {
  const res = await fetch(`${api}/admin/tenants`, {
    method: "POST",
    headers: { "X-Admin-Token": adminToken, "Content-Type": "application/json" },
    body: JSON.stringify({ name: `Dashboard e2e ${Date.now()}` }),
  });
  expect(res.status).toBe(201);
  return ((await res.json()) as { apiKey: TenantKey }).apiKey;
}

async function signIn(page: Page, key: string) {
  await page.goto("/login");
  await page.getByLabel("API key").fill(key);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
}

/** Picks the option whose text starts with the account name (options also show the balance). */
async function choose(page: Page, label: string, accountName: string) {
  const select = page.locator(`select[name="${label.toLowerCase()}"]`);
  const value = await select.locator("option", { hasText: new RegExp(`^${accountName}`) }).getAttribute("value");
  await select.selectOption(value!);
}

async function openAccount(page: Page, name: string, funding: boolean) {
  await page.goto("/accounts");
  await page.getByLabel("Name").fill(name);
  if (funding) await page.getByLabel(/Funding account/).check();
  await page.getByRole("button", { name: "Open account" }).click();
  await expect(page.locator(".alert.success")).toContainText("Account opened");
}

test.describe.configure({ mode: "serial" });

const received: { headers: IncomingHttpHeaders; body: Buffer }[] = [];
let receiver: Server;
let receiverUrl: string;
let tenant: TenantKey;

test.beforeAll(async () => {
  expect(adminToken, "ADMIN_TOKEN must be set").not.toBe("");
  tenant = await newTenant();
  receiver = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    received.push({ headers: req.headers, body: Buffer.concat(chunks) });
    res.writeHead(200).end();
  });
  await new Promise<void>((r) => receiver.listen(0, "127.0.0.1", r));
  receiverUrl = `http://127.0.0.1:${(receiver.address() as AddressInfo).port}/hook`;
});

test.afterAll(() => receiver?.close());

test("pages need a session, and bad keys are refused", async ({ page }) => {
  await page.goto("/accounts");
  await expect(page).toHaveURL(/\/login$/);

  await page.getByLabel("API key").fill("not-a-key");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator(".alert.error")).toContainText("doesn't look like");

  await page.getByLabel("API key").fill(`lf_test_${"0".repeat(12)}_${"0".repeat(64)}`);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator(".alert.error")).toContainText("wasn't accepted");
});

test("security headers are set", async ({ page }) => {
  const response = await page.goto("/login");
  const headers = response!.headers();
  expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("no-referrer");
  expect(headers["x-powered-by"]).toBeUndefined();
});

test("sign in, open accounts and move money", async ({ page, context }) => {
  await signIn(page, tenant.apiKey);
  await expect(page.getByText("Healthy")).toBeVisible();

  const cookie = (await context.cookies()).find((c) => c.name === "lf_session");
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Strict");
  expect(cookie?.value).not.toContain(tenant.apiKey);

  await openAccount(page, "Treasury", true);
  await openAccount(page, "Alice wallet", false);

  await page.goto("/transfers/new");
  await choose(page, "From", "Treasury");
  await choose(page, "To", "Alice wallet");
  await page.getByLabel("Amount").fill("250.00");
  // A double click must not move money twice: the form's idempotency key makes the second one a replay.
  await page.getByRole("button", { name: "Post transfer" }).dblclick();
  await expect(page.locator(".alert.success")).toContainText(/Transfer posted|already been made/);

  await page.waitForLoadState("networkidle"); // let the double click's second submit settle
  await page.goto((await page.getByRole("link", { name: "View transfer" }).getAttribute("href"))!);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("250,00");

  await page.getByRole("link", { name: "Alice wallet" }).click();
  await expect(page.getByTestId("balance")).toContainText("250,00");
  await expect(page.locator("tbody tr")).toHaveCount(1);   // exactly one posting

  // Insufficient funds comes back as the API's own explanation.
  await page.goto("/transfers/new");
  await choose(page, "From", "Alice wallet");
  await choose(page, "To", "Treasury");
  await page.getByLabel("Amount").fill("1000");
  await page.getByRole("button", { name: "Post transfer" }).click();
  await expect(page.locator(".alert.error")).toContainText("INSUFFICIENT_FUNDS");

  for (const path of ["/", "/accounts", "/transfers/new", "/webhooks"]) {
    await page.goto(path);
    expect(await page.content(), `${path} must not contain the API key`).not.toContain(tenant.apiKey);
  }
});

test("register a webhook, see its secret once, and receive a signed test event", async ({ page }) => {
  await signIn(page, tenant.apiKey);
  await page.goto("/webhooks");
  await page.getByLabel("URL").fill(receiverUrl);
  await page.getByLabel("account.created").check();
  await page.getByRole("button", { name: "Register endpoint" }).click();
  const secret = (await page.getByTestId("webhook-secret").textContent())!;
  expect(secret).toMatch(/^whsec_/);

  await page.reload();
  await expect(page.getByTestId("webhook-secret")).toHaveCount(0);
  expect(await page.content()).not.toContain(secret);

  await page.getByRole("link", { name: receiverUrl }).click();
  await page.getByRole("button", { name: "Send test event" }).click();
  await expect(page.locator(".alert.success")).toContainText("Test event queued");
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("SUCCEEDED")).toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 20_000 });

  const test = received.find((r) => r.body.toString().includes("webhook.test"));
  expect(test).toBeDefined();
  const event = await verifyWebhook(test!.body, test!.headers, secret);
  expect(event.type).toBe("webhook.test");

  await page.getByRole("button", { name: "Delete endpoint" }).click();
  await expect(page).toHaveURL(/\/webhooks\?notice=/);
  await expect(page.getByText("No endpoints yet.")).toBeVisible();
});

test("revoking the key ends the session; signing out works", async ({ page, context }) => {
  const other = await newTenant();
  await signIn(page, other.apiKey);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);

  await signIn(page, other.apiKey);
  const res = await fetch(`${api}/admin/api-keys/${other.keyId}`, {
    method: "DELETE", headers: { "X-Admin-Token": adminToken },
  });
  expect(res.ok).toBe(true);
  await page.goto("/accounts");
  await expect(page).toHaveURL(/\/login\?expired=1$/);
  expect((await context.cookies()).find((c) => c.name === "lf_session")).toBeUndefined();
});

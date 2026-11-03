import { defineConfig, devices } from "@playwright/test";

// Expects a LedgerForge API at LEDGERFORGE_API_URL (CI starts one with the dev profile, so the test's
// local webhook receiver is an allowed target) and ADMIN_TOKEN to create a tenant.
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run start",
    url: "http://localhost:3000/login",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: {
      PORT: "3000",
      LEDGERFORGE_API_URL: process.env.LEDGERFORGE_API_URL ?? "http://localhost:8080",
      DASHBOARD_SESSION_SECRET: process.env.DASHBOARD_SESSION_SECRET ?? "",
    },
  },
});

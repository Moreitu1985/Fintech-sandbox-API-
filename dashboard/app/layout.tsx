import type { Metadata } from "next";
import { connection } from "next/server";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "LedgerForge Dashboard", template: "%s · LedgerForge" },
  description: "Accounts, transfers, webhooks and ledger integrity for your LedgerForge tenant.",
  robots: { index: false, follow: false },
};

export default async function RootLayout({ children }: { children: ReactNode }) {
  // Render every page per request, so each one gets its own CSP nonce (proxy.ts).
  await connection();
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

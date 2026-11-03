/**
 * Banners shown after a redirect. Only these fixed messages can appear: a link can't put its own text
 * in a trusted-looking banner (which would be a phishing aid).
 */
export const NOTICES = {
  "test-queued": { kind: "success", text: "Test event queued. Refresh in a moment to see the delivery." },
  enabled: { kind: "success", text: "Endpoint enabled." },
  "retry-queued": { kind: "success", text: "Delivery queued again." },
  deleted: { kind: "success", text: "Endpoint deleted. Pending deliveries were cancelled." },
  failed: { kind: "error", text: "The API refused that change." },
  unavailable: { kind: "error", text: "The LedgerForge API can't be reached right now. Try again shortly." },
} as const;

export type NoticeCode = keyof typeof NOTICES;

export function notice(code: string | undefined, errorCode?: string): { kind: "success" | "error"; text: string } | null {
  if (!code || !(code in NOTICES)) return null;
  const n = NOTICES[code as NoticeCode];
  const detail = errorCode && /^[A-Z][A-Z0-9_]{1,40}$/.test(errorCode) ? ` (${errorCode})` : "";
  return { kind: n.kind, text: n.text + (n.kind === "error" ? detail : "") };
}

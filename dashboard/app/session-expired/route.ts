import type { NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session.ts";

/**
 * Reached when the API rejects the session's key (revoked or rotated): forget it and ask to sign in.
 * The redirect is relative, so it works behind any proxy or host name. A request started by another
 * site (a link elsewhere) can't sign the user out: it just lands on the dashboard.
 */
export function GET(request: NextRequest) {
  const crossSite = request.headers.get("sec-fetch-site") === "cross-site";
  const headers = new Headers({ Location: crossSite ? "/" : "/login?expired=1", "Cache-Control": "no-store" });
  if (!crossSite) {
    headers.append("Set-Cookie", `${SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`
      + (process.env.NODE_ENV === "production" ? "; Secure" : ""));
  }
  return new Response(null, { status: 303, headers });
}

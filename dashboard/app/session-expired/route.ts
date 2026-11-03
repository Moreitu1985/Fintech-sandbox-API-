import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/session.ts";

/** Reached when the API rejects the session's key (revoked or rotated): forget it and ask to sign in. */
export function GET(request: NextRequest) {
  const response = NextResponse.redirect(new URL("/login?expired=1", request.url), 303);
  response.cookies.delete(SESSION_COOKIE);
  return response;
}

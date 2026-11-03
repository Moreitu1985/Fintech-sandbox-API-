import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

// The SDK lives next door (../sdk/typescript): the build and the standalone output must see the whole repo.
const repoRoot = fileURLToPath(new URL("..", import.meta.url));

// The dashboard only ever talks to itself from the browser: API calls happen on the server, with the
// key from the encrypted session cookie. The policy below makes that explicit.
const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'", // Next.js bootstraps with small inline scripts
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "object-src 'none'",
].join("; ");

const config: NextConfig = {
  output: "standalone",
  turbopack: { root: repoRoot },
  outputFileTracingRoot: repoRoot,
  poweredByHeader: false,
  reactStrictMode: true,
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "Content-Security-Policy", value: contentSecurityPolicy },
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "Referrer-Policy", value: "no-referrer" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
        { key: "Cache-Control", value: "no-store" }, // pages show account data: never cache them
      ],
    }];
  },
};

export default config;

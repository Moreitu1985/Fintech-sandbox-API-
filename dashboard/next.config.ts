import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

// The SDK lives next door (../sdk/typescript): the build and the standalone output must see the whole repo.
const repoRoot = fileURLToPath(new URL("..", import.meta.url));

// The Content-Security-Policy is set per request (with a nonce) in proxy.ts.

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
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "Referrer-Policy", value: "no-referrer" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
        { key: "Cache-Control", value: "no-store" }, // pages show account data: never cache them
        ...(process.env.NODE_ENV === "production"
          ? [{ key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" }] : []),
      ],
    }];
  },
};

export default config;

# Build from the repository root (the dashboard uses the SDK next to it):
#   docker build -f dashboard/Dockerfile -t ledgerforge-dashboard .
FROM node:26-alpine AS build
WORKDIR /repo
ENV NEXT_TELEMETRY_DISABLED=1
COPY sdk/typescript/package.json sdk/typescript/package-lock.json sdk/typescript/
RUN cd sdk/typescript && npm ci --no-audit --no-fund
COPY sdk/typescript sdk/typescript
RUN cd sdk/typescript && npm run build
COPY dashboard/package.json dashboard/package-lock.json dashboard/
RUN cd dashboard && npm ci --no-audit --no-fund
COPY dashboard dashboard
RUN cd dashboard && npm run build

# Runtime: the standalone server only (no build tools, no npm), as a non-root user.
FROM node:26-alpine
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
              /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /opt/yarn-* /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && addgroup -S -g 10003 app && adduser -S -u 10003 -G app app
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
# Root-owned and read-only to the app user: the process can't rewrite its own code.
COPY --from=build /repo/dashboard/.next/standalone ./
USER app
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/login').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
# DASHBOARD_SESSION_SECRET (base64, 32 bytes) and LEDGERFORGE_API_URL must be set.
CMD ["node", "dashboard/server.js"]

# ADR 0008: Short-lived OAuth 2.0 tokens for clients, mutual TLS between services

**Status:** Accepted

## Context

Until now every call carried the long-lived API key: anything that logged, cached or leaked one request leaked the credential until someone revoked it, and every caller held full access. Between services, the ledger trusted whatever answered at the fraud service's address, protected only by a shared secret.

## Decision: OAuth 2.0 client credentials

- `POST /oauth/token` implements the client-credentials grant (RFC 6749 §4.4). The client authenticates with `client_id` (its API key id) and `client_secret` (its API key), by HTTP Basic or form fields, never both.
- It returns an **RS256-signed JWT** valid for **15 minutes**, with `iss`, `aud`, `sub` (key id), `tid` (tenant), `scope`, `jti`, `iat`, `nbf`, `exp`, and a `kid` header equal to the key's thumbprint.
- **Scopes** give least privilege: `ledger:read` (GET), `ledger:write` (POST), `assistant`. A reporting tool can hold a read-only token. An API key used directly still has all scopes, so existing clients keep working.
- Verification accepts only RS256 from our key, our issuer and our audience, and checks expiry (60 s clock skew). A verified token becomes the same tenant identity an API key produces, so tenant isolation is unchanged.
- Only bearer values shaped like a JWT go to the JWT verifier; API keys and the metrics token keep their own filters.
- Failures follow RFC 6749 §5.2 (`invalid_client`, `invalid_scope`, `unsupported_grant_type`, `invalid_request`). Every client-authentication failure looks the same. Responses carry `Cache-Control: no-store`. Every issued token is recorded (`ACCESS_TOKEN_ISSUED`, with its `jti`).
- `GET /oauth/jwks.json` publishes the public key (never the private parts), so other services can verify tokens themselves.
- The signing key comes from `LEDGERFORGE_OAUTH_SIGNING_KEY` (PKCS#8, RSA ≥ 2048) and must be shared by all instances. Without it a temporary key is generated, which suits development and the demo only.

## Decision: mutual TLS between the ledger and the fraud service

- The fraud service serves HTTPS and **requires a client certificate** signed by a dedicated CA. A caller without one is cut off during the TLS handshake, before any HTTP is read.
- The ledger presents its client certificate (a Spring Boot SSL bundle) and accepts **only a server certificate from that CA**, so an impostor service can't receive transfer data or approve a transfer.
- With a bundle configured, a plain `http://` URL is refused at startup: mutual TLS is never silently downgraded.
- The shared `X-Service-Token` stays as a second layer.
- TLS failures count as fraud-service outages, so the fail-open/fail-closed policy (ADR 0005) decides. A deployment that must never score without mTLS uses fail-closed.
- `scripts/generate-dev-certs.sh` creates a development CA (`docker compose` runs it in a one-shot `certs` service and deletes the CA key afterwards). Production certificates come from a real PKI or secrets manager.

## Consequences

- A leaked access token works for at most 15 minutes, and only within its scopes. A leaked API key is still serious, but clients that use tokens send it once every 15 minutes instead of on every request.
- **Revocation lag:** revoking an API key stops new tokens at once, but tokens already issued stay valid until they expire (at most 15 minutes). Instant revocation would need a token denylist checked on every request; the short lifetime was chosen instead.
- Requests with invalid tokens are rejected before the rate limiter, so they aren't counted against an IP. Forging a token is computationally infeasible, and each rejection costs only one RSA verification.
- Certificate-bound tokens (RFC 8705) would also stop a stolen token being replayed from another machine; they are a possible next step.
- The fraud service's health check can only confirm its port accepts connections, because mTLS refuses anonymous HTTP.

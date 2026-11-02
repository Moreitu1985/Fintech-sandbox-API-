# ADR 0008: Short-lived OAuth 2.0 tokens for clients, mutual TLS between services

**Status:** Accepted

## Context

Until now every call carried the long-lived API key: anything that logged, cached or leaked one request leaked the credential until someone revoked it, and every caller held full access. Between services, the ledger trusted whatever answered at the fraud service's address, protected only by a shared secret.

## Decision: OAuth 2.0 client credentials

- `POST /oauth/token` implements the client-credentials grant (RFC 6749 §4.4). The client authenticates with `client_id` (its API key id) and `client_secret` (its API key), by HTTP Basic or form fields, never both.
- It returns an **RS256-signed JWT** valid for **15 minutes**, with `iss`, `aud`, `sub` (key id), `tid` (tenant), `scope`, `jti`, `iat`, `nbf`, `exp`, and a `kid` header equal to the key's thumbprint.
- **Scopes** give least privilege: `ledger:read` (GET, HEAD), `ledger:write` (other methods), `assistant`. The assistant reads the ledger on the caller's behalf, so it needs `ledger:read` as well as `assistant`. A reporting tool can hold a read-only token. An API key used directly still has all scopes, so existing clients keep working.
- Verification accepts only RS256 tokens typed `at+jwt` (RFC 9068) from our key, issuer and audience, requires `exp`, `iat` and `jti`, rejects any token claiming a lifetime longer than the configured one, and checks expiry (60 s clock skew). A verified token becomes the same tenant identity an API key produces, so tenant isolation is unchanged.
- Only bearer values shaped like a JWT go to the JWT verifier; API keys and the metrics token keep their own filters.
- Failures follow RFC 6749 §5.2 (`invalid_client`, `invalid_scope`, `unsupported_grant_type`, `invalid_request`). Every client-authentication failure looks the same. Parameters in the URL (where an API key would land in logs), repeated parameters and two authentication methods at once are refused. Responses carry `Cache-Control: no-store`. Every issued token is recorded (`ACCESS_TOKEN_ISSUED`, with its `jti`).
- Each client may request 10 tokens a minute, counted after authentication so nobody can spend another client's budget. This bounds the audit writes (each briefly locks the tenant) and nudges clients to reuse tokens.
- `GET /oauth/jwks.json` publishes the public key (never the private parts), so other services can verify tokens themselves.
- The signing key comes from `LEDGERFORGE_OAUTH_SIGNING_KEY_FILE` (preferred: a mounted secret) or `LEDGERFORGE_OAUTH_SIGNING_KEY` (PKCS#8, RSA ≥ 2048) and must be shared by all instances. The `prod` profile refuses to start without one; elsewhere a temporary key is generated, which suits development and the demo only.

## Decision: mutual TLS between the ledger and the fraud service

- The fraud service serves HTTPS and **requires a client certificate** signed by a CA dedicated to this one link, so holding any certificate from it is the authorization (the client's name is not checked separately). A caller without one is cut off during the TLS handshake, before any HTTP is read. Setting only some of the TLS variables is refused, so it never falls back to plain HTTP.
- The ledger presents its client certificate (a Spring Boot SSL bundle) and accepts **only a server certificate from that CA**, so an impostor service can't receive transfer data or approve a transfer.
- With a bundle configured, a plain `http://` URL is refused at startup: mutual TLS is never silently downgraded.
- The shared `X-Service-Token` stays as a second layer.
- **A TLS failure is a security event, not an outage.** An impostor, an untrusted or expired certificate, or a missing client certificate always refuses the transfer, even with fail-open on. Only genuine outages (timeouts, refused connections, 5xx) follow the fail-open policy.
- `scripts/generate-dev-certs.sh` creates a development CA. `docker compose` runs it in a one-shot `certs` service that gives each service its own folder, readable only by that service's user (mode 0400 keys), generates the token-signing key as a file, and deletes the CA key. Production certificates come from a real PKI or secrets manager.
- Test-only certificates live in `src/test/resources/mtls` (CA keys deleted) and are allowlisted for secret scanners in `.gitleaks.toml`.

## Consequences

- A leaked access token works for at most 15 minutes, and only within its scopes. A leaked API key is still serious, but clients that use tokens send it once every 15 minutes instead of on every request.
- **Revocation lag:** revoking an API key stops new tokens at once, but tokens already issued stay valid until they expire (at most 15 minutes). Instant revocation would need a token denylist checked on every request; the short lifetime was chosen instead.
- Requests with invalid tokens are rejected before the rate limiter, so they aren't counted against an IP. Forging a token is computationally infeasible, and each rejection costs only one RSA verification.
- Certificate-bound tokens (RFC 8705) would also stop a stolen token being replayed from another machine; they are a possible next step.
- The fraud service's health check can only confirm its port accepts connections, because mTLS refuses anonymous HTTP.

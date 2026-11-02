# ADR 0010: Webhooks through a transactional outbox, signed in the Standard Webhooks format

**Status:** Accepted

## Context

Fintechs need to react when money moves (update a customer's app, release goods, reconcile) without polling the API. A notification must never be sent for a transfer that didn't happen, and never be lost for one that did. A webhook system also lets a tenant make the platform send HTTP requests to an address of their choosing, which is a classic way into a provider's internal network (server-side request forgery).

## Decision

- **Transactional outbox.** `transfer.posted` and `account.created` events are inserted in the same database transaction as the postings or the new account. A rolled-back transfer leaves no event; a crash just after commit loses nothing, because the rows are already there. Only tenants with a matching endpoint pay for the insert.
- **Dispatcher on every instance.** It polls for due deliveries and claims each one with a compare-and-set on its next-attempt time, which also acts as a lease: one instance sends it, and if that instance dies the lease runs out and another picks it up. The lease time is also a fencing token: an instance whose lease was taken over can't record its result over the new holder's, and a version column catches a takeover between the check and the commit. This works on PostgreSQL and H2 without `SKIP LOCKED`, and is tested with eight concurrent claimers on PostgreSQL. HTTP calls run on virtual threads, outside any database transaction.
- **Fair sharing.** Each instance has 16 send slots; one endpoint may use at most 2 and one tenant at most 4, and endpoints are served longest-waiting first, so a slow or dead receiver delays only its own events.
- **At-least-once delivery.** Retries after 30 s, 2 min, 10 min, 1 h, 3 h, 6 h and 12 h (plus up to 10% jitter), about a day in all. The `webhook-id` (the event id) and the body are identical on every retry, so receivers de-duplicate on it. After five deliveries in a row give up, the endpoint is disabled and the tenant can re-enable it and retry individual deliveries.
- **Standard Webhooks signatures:** `webhook-id`, `webhook-timestamp` and `webhook-signature: v1,base64(HMAC-SHA256(...))`. Receivers can use the open-source verification libraries in most languages, and the timestamp lets them reject replays. Secrets are `whsec_` + 32 random bytes, shown once at creation or rotation.
- **Secrets encrypted at rest** with AES-256-GCM, bound to the endpoint id as associated data. They can't be hashed like API keys, because every delivery needs the plain secret to sign. The key comes from configuration, and the `prod` profile refuses to start without it.
- **SSRF guard.** HTTPS on port 443 only, no credentials in the URL, and every resolved address must be public. Loopback, private, link-local (including 169.254.169.254), carrier-grade NAT, IPv6 unique-local and documentation ranges are refused, including IPv4-mapped forms. The check runs at registration and again before every send, because DNS can change. Redirects are never followed, and only the status code is read: the response body is closed unread, so a hostile receiver can't stream data into the platform or hold a sender. Development and tests allow local receivers through one explicit flag. DNS lookups time out after 2 seconds and run outside database transactions, so a slow name server can't tie up connections. An unknown host and a private one get the same answer, so tenants can't probe internal names. 6to4 and Teredo IPv6 addresses, which tunnel to an embedded IPv4 address, are refused too.
- **Least privilege.** A webhook streams ledger data out, so managing webhooks needs both `ledger:read` and `ledger:write`.
- **Audit.** Creating, deleting, rotating, enabling and automatic disabling of endpoints are recorded in the tenant's hash-chained audit log, with the URL's host only: paths and queries can carry tokens, and the chain keeps everything.

## Consequences

- Receivers must handle duplicates and events arriving out of order (a retry can arrive after a later event). The event's `createdAt` and the transfer's own timestamps give the order.
- Delivery adds latency of up to one poll interval (1 second by default) after the commit.
- Rotating a secret takes effect at once; a grace period that signs with both secrets for a while is a possible next step (the Standard Webhooks format allows several signatures).
- **DNS rebinding:** a host could resolve to a public address during the check and a private one during the connection. In Kubernetes the egress NetworkPolicy blocks private ranges as a second layer; connecting to the exact address that was checked would close the gap fully.
- Old events and deliveries are kept; a retention job is needed before the tables get large.

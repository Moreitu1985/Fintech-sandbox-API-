# ADR 0005: Fraud scoring as a synchronous plugin calling a separate ML service

**Status:** Accepted

## Context

Fraud must be stopped before money moves, not discovered afterwards. The machine-learning ecosystem lives in Python, while the ledger is Java. The ledger core must not change to add fraud checks (ADR on plugins), and a fraud-service outage must never corrupt the books.

## Decision

- **Separate service.** `fraud-service` (Python, FastAPI, LightGBM) owns the model. The ledger owns the data.
- **The ledger computes the features.** The `fraud-check` plugin derives behaviour features from the ledger's own postings inside the transfer's transaction. The model service stays stateless and sees no personal data, and its feature list is validated on both sides.
- **Synchronous, before any lock.** A new plugin hook, `beforeLocking`, runs inside the transfer's transaction but before the tenant and account locks are taken, so a slow model call never stalls the tenant's other payments. Features are read from committed, unlocked data; the balance can change in the moment between scoring and locking, which the core's own balance check still covers.
- **BLOCK** throws, so the transfer rolls back and no money moves. A `FRAUD_BLOCK` audit event is committed in its own transaction (`REQUIRES_NEW`) so the evidence survives the rollback. The caller only sees "declined by risk checks": the reasons stay in the audit log, so they can't be used to tune an attack.
- **REVIEW** writes a `FRAUD_REVIEW` audit event in the transfer's transaction.
- Retries of a completed transfer are answered from the idempotency record before scoring, so they are never re-scored.
- **Short timeouts** (500 ms connect, 1.5 s read) and an explicit **fail-open / fail-closed** switch for genuine outages (timeouts, connection errors, 5xx). The sandbox defaults to fail-open; a production deployment handling real money would choose fail-closed or a fallback rule set.
- **Misconfiguration is never an outage.** A rejected token, a 4xx, or an unreadable response always refuses the transfer and logs an error, so a broken setup can't silently switch scoring off. Java clamps every feature to the ranges the service accepts.
- **Only ZAR transfers are scored**, the currency the model was trained on.
- **Funding accounts are not scored.** Transfers from `allowNegative` accounts are the platform's own treasury operations.
- **Service-to-service auth** with a 32+ character shared secret, compared in constant time. The service is not published outside the Docker network.
- **Explainability:** the response carries the top TreeSHAP contributions as plain sentences, so every BLOCK can be explained.

## Consequences

- A slow fraud check adds latency to that one transfer (capped by the timeouts) but not to others, because no lock is held. The database connection is still held during the call; moving scoring outside the transaction would release it, at the cost of more complex retry handling.
- The model is trained on synthetic data. Its numbers describe the simulator, not real fraud (see `fraud-service/MODEL_CARD.md`).
- Thresholds are part of the model artifact. Changing risk appetite means retraining or re-thresholding, which is tracked by the model version in every audit event.

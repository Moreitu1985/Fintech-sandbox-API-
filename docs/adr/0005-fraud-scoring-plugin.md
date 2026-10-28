# ADR 0005: Fraud scoring as a synchronous plugin calling a separate ML service

**Status:** Accepted

## Context

Fraud must be stopped before money moves, not discovered afterwards. The machine-learning ecosystem lives in Python, while the ledger is Java. The ledger core must not change to add fraud checks (ADR on plugins), and a fraud-service outage must never corrupt the books.

## Decision

- **Separate service.** `fraud-service` (Python, FastAPI, LightGBM) owns the model. The ledger owns the data.
- **The ledger computes the features.** The `fraud-check` plugin derives behaviour features from the ledger's own postings inside the transfer's transaction, with both accounts locked. The model service stays stateless and sees no personal data, and its feature list is validated on both sides.
- **Synchronous, inside the transaction.** `beforeTransfer` calls the service before any posting is written. BLOCK throws, so the whole transaction rolls back. REVIEW writes a `FRAUD_REVIEW` audit event in the same transaction.
- **Short timeouts** (500 ms connect, 1.5 s read) and an explicit **fail-open / fail-closed** switch for outages. The sandbox defaults to fail-open; a production deployment handling real money would choose fail-closed or a fallback rule set.
- **Funding accounts are not scored.** Transfers from `allowNegative` accounts are the platform's own treasury operations.
- **Service-to-service auth** with a 32+ character shared secret, compared in constant time. The service is not published outside the Docker network.
- **Explainability:** the response carries the top TreeSHAP contributions as plain sentences, so every BLOCK can be explained.

## Consequences

- A slow fraud check adds latency while the account locks are held. Timeouts cap this. If it becomes a problem, scoring can move before lock acquisition, using a snapshot of the features.
- The model is trained on synthetic data. Its numbers describe the simulator, not real fraud (see `fraud-service/MODEL_CARD.md`).
- Thresholds are part of the model artifact. Changing risk appetite means retraining or re-thresholding, which is tracked by the model version in every audit event.

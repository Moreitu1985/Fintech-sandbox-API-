# Model card: LedgerForge fraud model (`fraud-lgbm-1.0.0`)

## Purpose

Scores each ledger transfer for fraud risk before money moves, and returns **ALLOW**, **REVIEW** or **BLOCK** with the top reasons. It is a decision aid: REVIEW means a human looks, and BLOCK stops the transfer.

## Important limitation: synthetic data

The model is trained and evaluated on **simulated** transfers (`fraud_service/synth.py`), because real fraud data is private. The numbers below measure how well it learns the simulator's patterns. They are **not** a claim about real-world fraud. Before production use, it must be retrained and re-evaluated on real, labelled transaction history, and monitored for drift.

## Model

- LightGBM gradient-boosted trees, binary objective, class weighting for the 2% fraud rate, early stopping on validation.
- Explanations: per-feature TreeSHAP contributions (`pred_contrib`), translated into plain sentences. A sentence states a specific risk ("Account is new", "Late-night transfer") only when the value really is in that range; otherwise it says neutrally that the feature raised the score. A test checks this on 400 fraudulent transfers.
- Reproducible: fixed seed, deterministic training, trained inside the Docker build in about 15 seconds.

## Features (computed by the ledger at transfer time)

| Feature | Meaning |
|---|---|
| `amount` | Transfer amount (major units) |
| `amount_to_avg_ratio` | Amount ÷ the account's average outgoing amount over 30 days |
| `account_age_hours` | Age of the sending account |
| `tx_count_1h` | Outgoing transfers in the previous hour |
| `amount_sum_24h` | Amount sent in the 24 hours up to and including this transfer |
| `new_counterparty` | First transfer to this recipient |
| `balance_drain_ratio` | Share of the available balance this transfer uses |
| `hour_of_day` | Local hour (Africa/Johannesburg) |
| `prior_tx_count` | Lifetime outgoing transfers |

No personal data (names, ID numbers, locations) is used.

## Simulated fraud patterns

| Pattern | Share of fraud | Signature |
|---|---|---|
| Account takeover | 45% | Large jump in amount, new recipient, drains the balance, often at night; a quarter stay modest |
| Velocity burst | 30% | Many small transfers in an hour, often at night |
| New-account mule | 25% | Days-old account forwarding a large sum to a new recipient |

Legitimate data includes overlaps on purpose: rent and big purchases, bill-splitting bursts, new customers and night-shift workers.

## Decision thresholds

Chosen on the **validation** set only:

- **REVIEW** at the score where at most **1%** of legitimate transfers are flagged.
- **BLOCK** at the score where at most **0.1%** of legitimate transfers are flagged.

## Results on the held-out test set

Test set: 40,000 transfers, 800 fraudulent, never used for training or tuning (200,000-row training run, seed 7).

| | Precision | Recall | False positive rate |
|---|---|---|---|
| **REVIEW threshold** | 66.8% | **95.6%** | 0.97% |
| **BLOCK threshold** | **96.6%** | 77.1% | 0.06% |
| Hand-written rule (ratio > 5 and new recipient) | 36.9% | 25.5% | 0.89% |

ROC AUC 0.998 · PR AUC 0.952

Recall by pattern (REVIEW / BLOCK): account takeover 93% / 57%, velocity burst 96% / 91%, new-account mule 100% / 96%.

At almost the same false-alarm rate, the model catches **3.7× more fraud** than the hand-written rule. Account takeovers are the hardest pattern: 93% are caught, but 43% of them only reach REVIEW rather than BLOCK, so a human makes the final call on those.

## Quality gates

`fraud_service/gates.py` runs in CI (on a model retrained in the tests) **and in the Docker build** (on the exact model that ships), failing either if the model gets worse: ROC AUC below 0.97, PR AUC below 0.80, BLOCK precision below 85%, BLOCK false positive rate above 0.3%, recall for any pattern below 80%, or not beating the hand-written rule by 30 points of recall.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Synthetic data differs from reality | Stated above; retrain on real data before production |
| Blocking a genuine customer | BLOCK is tuned to 0.1% of legitimate traffic; reasons let support explain and override |
| Attackers learn to stay under thresholds | Modest takeovers are still mostly caught at REVIEW; thresholds and features should be reviewed regularly |
| Service outage stopping payments | The ledger's plugin scores before taking locks, has short timeouts, and a fail-open or fail-closed policy for outages |
| Reasons used to tune an attack | Reasons go to the audit log, not to the API caller, who only sees "declined by risk checks" |
| Misconfiguration silently disabling scoring | A rejected token or invalid request is never treated as an outage: the transfer is refused and an error is logged |

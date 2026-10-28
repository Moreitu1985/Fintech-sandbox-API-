# LedgerForge Fraud Service

Python / FastAPI service that scores ledger transfers for fraud with a LightGBM model and explains every decision. The ledger calls it through its `fraud-check` plugin before any money moves.

See [MODEL_CARD.md](MODEL_CARD.md) for data, thresholds, held-out results and limitations.

## Run locally

```bash
cd fraud-service
python -m venv .venv && . .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
python -m fraud_service.train                      # writes ./model (about 15 s)
export FRAUD_SERVICE_TOKEN=$(openssl rand -hex 32)  # Windows: $env:FRAUD_SERVICE_TOKEN="<32+ chars>"
uvicorn fraud_service.api:app --port 8090
```

Interactive docs: set `FRAUD_SERVICE_DOCS=true`, then open <http://localhost:8090/docs> (off by default).

## API

`POST /v1/score` (header `X-Service-Token`)

```json
{
  "transfer_reference": "optional-ref",
  "features": {
    "amount": 38000, "amount_to_avg_ratio": 14, "account_age_hours": 12000,
    "tx_count_1h": 1, "amount_sum_24h": 40000, "new_counterparty": true,
    "balance_drain_ratio": 0.97, "hour_of_day": 2, "prior_tx_count": 800
  }
}
```

Response:

```json
{
  "score": 0.999743,
  "decision": "BLOCK",
  "reasons": [
    {
      "feature": "balance_drain_ratio",
      "contribution": 10.3895,
      "message": "Transfer uses 97% of the available balance"
    },
    {
      "feature": "new_counterparty",
      "contribution": 3.9534,
      "message": "First transfer to this recipient"
    },
    {
      "feature": "hour_of_day",
      "contribution": 3.2111,
      "message": "Late-night transfer (02:00)"
    }
  ],
  "model_version": "fraud-lgbm-1.0.0"
}
```

Other endpoints: `GET /health` (public) and `GET /v1/model` (thresholds and held-out metrics, token required).

## Tests

```bash
pytest
```

The tests include API behaviour, authentication, input validation, a check that reason messages never state something untrue, and **model quality gates** (`fraud_service/gates.py`). The Docker build runs the same gates on the model it ships.

from tests.conftest import TOKEN

AUTH = {"X-Service-Token": TOKEN}

NORMAL = {
    "amount": 350.0, "amount_to_avg_ratio": 0.9, "account_age_hours": 9000, "tx_count_1h": 0,
    "amount_sum_24h": 600.0, "new_counterparty": False, "balance_drain_ratio": 0.08,
    "hour_of_day": 13, "prior_tx_count": 420,
}
TAKEOVER = {
    "amount": 38000.0, "amount_to_avg_ratio": 14.0, "account_age_hours": 12000, "tx_count_1h": 1,
    "amount_sum_24h": 40000.0, "new_counterparty": True, "balance_drain_ratio": 0.97,
    "hour_of_day": 2, "prior_tx_count": 800,
}
BURST = {
    "amount": 120.0, "amount_to_avg_ratio": 0.4, "account_age_hours": 5000, "tx_count_1h": 25,
    "amount_sum_24h": 4200.0, "new_counterparty": True, "balance_drain_ratio": 0.05,
    "hour_of_day": 1, "prior_tx_count": 200,
}


def score(client, features):
    return client.post("/v1/score", json={"features": features}, headers=AUTH)


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "UP"


def test_scoring_requires_the_service_token(client):
    assert client.post("/v1/score", json={"features": NORMAL}).status_code == 401
    assert client.post("/v1/score", json={"features": NORMAL},
                       headers={"X-Service-Token": "wrong" * 10}).status_code == 401
    assert client.get("/v1/model").status_code == 401


def test_normal_transfer_is_allowed_without_reasons(client):
    body = score(client, NORMAL).json()
    assert body["decision"] == "ALLOW"
    assert body["reasons"] == []
    assert body["model_version"].startswith("fraud-lgbm")


def test_account_takeover_is_blocked_and_explained(client):
    body = score(client, TAKEOVER).json()
    assert body["decision"] == "BLOCK"
    features = {r["feature"] for r in body["reasons"]}
    assert features & {"balance_drain_ratio", "amount_to_avg_ratio", "amount"}
    assert all(r["contribution"] > 0 for r in body["reasons"])
    assert 1 <= len(body["reasons"]) <= 3


def test_velocity_burst_is_flagged(client):
    body = score(client, BURST).json()
    assert body["decision"] in {"REVIEW", "BLOCK"}
    assert "tx_count_1h" in {r["feature"] for r in body["reasons"]}


def test_invalid_input_is_rejected(client):
    bad = dict(NORMAL, balance_drain_ratio=1.5)
    assert score(client, bad).status_code == 422
    missing = {k: v for k, v in NORMAL.items() if k != "amount"}
    assert score(client, missing).status_code == 422
    assert score(client, dict(NORMAL, amount=-5)).status_code == 422


def test_model_info_reports_held_out_metrics(client):
    info = client.get("/v1/model", headers=AUTH).json()
    assert info["thresholds"]["block"] >= info["thresholds"]["review"]
    assert info["metrics"]["test_fraud_count"] > 0

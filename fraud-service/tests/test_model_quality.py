"""Quality gates: a retrained model that gets worse fails CI instead of shipping silently."""

import json

from fraud_service.features import TransferFeatures
from fraud_service.gates import check
from fraud_service.scorer import FraudScorer
from fraud_service.synth import generate


def load(model_dir):
    return json.loads((model_dir / "metadata.json").read_text())


def test_model_passes_all_quality_gates(model_dir):
    assert check(load(model_dir)) == []


def test_gates_catch_a_worse_model(model_dir):
    worse = load(model_dir)
    worse["metrics"]["roc_auc"] = 0.9
    worse["metrics"]["at_block_threshold"]["precision"] = 0.5
    assert len(check(worse)) >= 2


def test_reasons_never_state_something_untrue(model_dir):
    """Risk-specific wording appears only when the value really is in the risky range."""
    scorer = FraudScorer(model_dir)
    frame = generate(n=20_000, seed=99)
    fraud = frame[frame["is_fraud"] == 1].head(400)
    checked = 0
    for row in fraud.to_dict("records"):
        f = TransferFeatures(**{k: row[k] for k in TransferFeatures.model_fields},)
        for reason in scorer.score(f).reasons:
            checked += 1
            msg = reason.message
            if msg.startswith("Account is new"):
                assert f.account_age_hours < 168, msg
            if msg.startswith("Little account history"):
                assert f.prior_tx_count < 5, msg
            if "x this account's usual transfer" in msg and msg.startswith("Amount is"):
                assert f.amount_to_avg_ratio >= 2, msg
            if msg.startswith("Late-night"):
                assert f.hour_of_day >= 22 or f.hour_of_day <= 5, msg
            if "of the available balance" in msg and msg.startswith("Transfer uses"):
                assert f.balance_drain_ratio >= 0.5, msg
            assert msg != "Known recipient"
    assert checked > 100

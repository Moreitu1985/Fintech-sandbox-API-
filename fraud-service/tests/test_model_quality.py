"""Quality gates: a retrained model that gets worse fails CI instead of shipping silently."""

import json


def load_metrics(model_dir):
    return json.loads((model_dir / "metadata.json").read_text())


def test_model_beats_the_hand_written_rule(model_dir):
    m = load_metrics(model_dir)["metrics"]
    model, rule = m["at_review_threshold"], m["baseline_rule"]
    assert model["false_positive_rate"] <= 0.015
    assert model["recall"] > rule["recall"] + 0.3


def test_minimum_quality(model_dir):
    m = load_metrics(model_dir)["metrics"]
    assert m["roc_auc"] >= 0.97
    assert m["pr_auc"] >= 0.80
    assert m["at_block_threshold"]["precision"] >= 0.85
    assert m["at_block_threshold"]["false_positive_rate"] <= 0.003


def test_every_fraud_pattern_is_detected(model_dir):
    for pattern, stats in load_metrics(model_dir)["metrics"]["by_pattern"].items():
        assert stats["recall_at_review"] >= 0.8, pattern


def test_thresholds_respect_policy(model_dir):
    meta = load_metrics(model_dir)
    assert meta["thresholds"]["block"] >= meta["thresholds"]["review"]
    assert meta["features"]

"""Train the fraud model, choose decision thresholds, and evaluate on held-out data.

Splits: 60% train, 20% validation (early stopping and threshold choice), 20% test
(reported once, never used for any decision). Run:  python -m fraud_service.train
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import lightgbm as lgb
import numpy as np
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split

from .features import FEATURES
from .synth import generate

MODEL_VERSION = "fraud-lgbm-1.0.0"
REVIEW_TARGET_FPR = 0.01    # at most 1% of legitimate transfers sent to review
BLOCK_TARGET_FPR = 0.001    # at most 0.1% of legitimate transfers blocked
SEED = 7


def _threshold_at_fpr(scores_legit: np.ndarray, target_fpr: float) -> float:
    """Smallest score threshold whose false positive rate on legit transfers is <= target."""
    return float(np.quantile(scores_legit, 1.0 - target_fpr))


def _rates(y: np.ndarray, flagged: np.ndarray) -> dict[str, float]:
    tp = int(((flagged == 1) & (y == 1)).sum())
    fp = int(((flagged == 1) & (y == 0)).sum())
    fn = int(((flagged == 0) & (y == 1)).sum())
    tn = int(((flagged == 0) & (y == 0)).sum())
    return {
        "precision": round(tp / (tp + fp), 4) if tp + fp else 0.0,
        "recall": round(tp / (tp + fn), 4) if tp + fn else 0.0,
        "false_positive_rate": round(fp / (fp + tn), 5) if fp + tn else 0.0,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def baseline_rule(frame) -> np.ndarray:
    """What a simple hand-written rule catches: big jump in amount to a new counterparty."""
    return ((frame["amount_to_avg_ratio"] > 5) & (frame["new_counterparty"] == 1)).astype(int).to_numpy()


def train(out_dir: Path, n: int = 200_000) -> dict:
    df = generate(n=n, seed=SEED)
    train_df, rest = train_test_split(df, test_size=0.4, stratify=df["is_fraud"], random_state=SEED)
    valid_df, test_df = train_test_split(rest, test_size=0.5, stratify=rest["is_fraud"], random_state=SEED)

    pos = train_df["is_fraud"].sum()
    params = {
        "objective": "binary", "learning_rate": 0.05, "num_leaves": 31, "min_data_in_leaf": 50,
        "feature_fraction": 0.9, "bagging_fraction": 0.8, "bagging_freq": 1,
        "scale_pos_weight": float((len(train_df) - pos) / pos), "seed": SEED,
        "deterministic": True, "force_col_wise": True, "verbose": -1,
    }
    dtrain = lgb.Dataset(train_df[FEATURES], label=train_df["is_fraud"])
    dvalid = lgb.Dataset(valid_df[FEATURES], label=valid_df["is_fraud"], reference=dtrain)
    booster = lgb.train(params, dtrain, num_boost_round=1000, valid_sets=[dvalid],
                        callbacks=[lgb.early_stopping(50, verbose=False)])

    # Thresholds are chosen on validation only.
    v_scores = booster.predict(valid_df[FEATURES], num_iteration=booster.best_iteration)
    v_legit = v_scores[valid_df["is_fraud"].to_numpy() == 0]
    review_t = _threshold_at_fpr(v_legit, REVIEW_TARGET_FPR)
    block_t = max(_threshold_at_fpr(v_legit, BLOCK_TARGET_FPR), review_t)

    # Test set: reported, never tuned on.
    y = test_df["is_fraud"].to_numpy()
    t_scores = booster.predict(test_df[FEATURES], num_iteration=booster.best_iteration)
    by_pattern = {}
    for pattern in sorted(test_df["pattern"].unique()):
        if pattern == "legit":
            continue
        mask = (test_df["pattern"] == pattern).to_numpy()
        by_pattern[pattern] = {
            "count": int(mask.sum()),
            "recall_at_review": round(float((t_scores[mask] >= review_t).mean()), 4),
            "recall_at_block": round(float((t_scores[mask] >= block_t).mean()), 4),
        }

    metrics = {
        "test_size": int(len(test_df)),
        "test_fraud_count": int(y.sum()),
        "roc_auc": round(float(roc_auc_score(y, t_scores)), 4),
        "pr_auc": round(float(average_precision_score(y, t_scores)), 4),
        "at_review_threshold": _rates(y, (t_scores >= review_t).astype(int)),
        "at_block_threshold": _rates(y, (t_scores >= block_t).astype(int)),
        "baseline_rule": _rates(y, baseline_rule(test_df)),
        "by_pattern": by_pattern,
    }
    metadata = {
        "model_version": MODEL_VERSION,
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "features": FEATURES,
        "best_iteration": int(booster.best_iteration),
        "thresholds": {"review": round(review_t, 6), "block": round(block_t, 6)},
        "threshold_policy": {"review_target_fpr": REVIEW_TARGET_FPR, "block_target_fpr": BLOCK_TARGET_FPR},
        "training_data": {"source": "synthetic (fraud_service.synth)", "rows": n, "fraud_rate": 0.02, "seed": SEED},
        "metrics": metrics,
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(out_dir / "model.txt"), num_iteration=booster.best_iteration)
    (out_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
    return metadata


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "model"
    meta = train(out)
    print(json.dumps(meta["metrics"], indent=2))
    print("thresholds:", meta["thresholds"])

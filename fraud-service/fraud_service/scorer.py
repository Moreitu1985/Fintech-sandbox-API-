"""Loads the trained model and turns a feature vector into a decision with reasons.

Reasons come from LightGBM's per-feature contributions (TreeSHAP values in log-odds):
the features that pushed this transfer's score up the most are translated into plain
sentences, so every REVIEW or BLOCK can be explained to an analyst or a customer.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

import lightgbm as lgb
import numpy as np

from .features import FEATURES, TransferFeatures


class Decision(str, Enum):
    ALLOW = "ALLOW"
    REVIEW = "REVIEW"
    BLOCK = "BLOCK"


@dataclass(frozen=True)
class Reason:
    feature: str
    contribution: float
    message: str


@dataclass(frozen=True)
class Score:
    score: float
    decision: Decision
    reasons: list[Reason]
    model_version: str


def _describe(feature: str, f: TransferFeatures) -> str | None:
    """Plain-language reason for a feature that pushed the score up.

    A risk-specific sentence is used only when the value really is in the risky range; otherwise the
    wording stays neutral ("... raised the risk"), so a reason can never state something untrue
    (e.g. calling an old account "new"). Returns None when the value is not a meaningful risk signal.
    """
    match feature:
        case "amount":
            return f"Amount of {f.amount:,.2f}"
        case "amount_to_avg_ratio":
            if f.amount_to_avg_ratio >= 2:
                return f"Amount is {f.amount_to_avg_ratio:.1f}x this account's usual transfer"
            return f"Amount relative to this account's usual transfer ({f.amount_to_avg_ratio:.1f}x) raised the risk"
        case "account_age_hours":
            if f.account_age_hours < 168:
                return f"Account is new ({f.account_age_hours:.0f} hours old)"
            return f"Account age ({f.account_age_hours / 24:.0f} days) raised the risk"
        case "tx_count_1h":
            if f.tx_count_1h >= 3:
                return f"{f.tx_count_1h} transfers from this account in the last hour"
            return f"Recent activity ({f.tx_count_1h} transfers in the last hour) raised the risk"
        case "amount_sum_24h":
            return f"{f.amount_sum_24h:,.2f} sent from this account in the last 24 hours"
        case "new_counterparty":
            return "First transfer to this recipient" if f.new_counterparty else None
        case "balance_drain_ratio":
            if f.balance_drain_ratio >= 0.5:
                return f"Transfer uses {f.balance_drain_ratio:.0%} of the available balance"
            return f"Share of balance used ({f.balance_drain_ratio:.0%}) raised the risk"
        case "hour_of_day":
            if f.hour_of_day >= 22 or f.hour_of_day <= 5:
                return f"Late-night transfer ({f.hour_of_day:02d}:00)"
            return f"Time of day ({f.hour_of_day:02d}:00) raised the risk"
        case "prior_tx_count":
            if f.prior_tx_count < 5:
                return f"Little account history ({f.prior_tx_count} previous transfers)"
            return f"Account history ({f.prior_tx_count} previous transfers) raised the risk"
    return None


class FraudScorer:
    def __init__(self, model_dir: Path):
        self.booster = lgb.Booster(model_file=str(model_dir / "model.txt"))
        self.metadata = json.loads((model_dir / "metadata.json").read_text())
        if self.metadata["features"] != FEATURES:
            raise RuntimeError("Model was trained on a different feature list than this service expects")
        self.review_threshold = float(self.metadata["thresholds"]["review"])
        self.block_threshold = float(self.metadata["thresholds"]["block"])
        self.model_version = self.metadata["model_version"]

    def score(self, features: TransferFeatures, max_reasons: int = 3) -> Score:
        row = np.array([features.as_row()])
        probability = float(self.booster.predict(row)[0])
        contributions = self.booster.predict(row, pred_contrib=True)[0][:-1]  # last column is the bias

        if probability >= self.block_threshold:
            decision = Decision.BLOCK
        elif probability >= self.review_threshold:
            decision = Decision.REVIEW
        else:
            decision = Decision.ALLOW

        reasons: list[Reason] = []
        if decision is not Decision.ALLOW:
            ranked = sorted(zip(FEATURES, contributions), key=lambda kv: kv[1], reverse=True)
            for name, c in ranked:
                if c <= 0 or len(reasons) == max_reasons:
                    break
                message = _describe(name, features)
                if message is not None:
                    reasons.append(Reason(name, round(float(c), 4), message))
        return Score(round(probability, 6), decision, reasons, self.model_version)

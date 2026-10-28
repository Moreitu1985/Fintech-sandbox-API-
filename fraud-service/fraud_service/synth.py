"""Synthetic transfer data with labelled fraud patterns.

Real fraud data is private and highly imbalanced, so the model is trained on a simulator.
The simulator deliberately makes fraud overlap with unusual-but-legitimate behaviour
(salary days, rent, big one-off purchases, night shift workers) so the model has to learn
combinations of signals rather than a single threshold. Metrics reflect this simulator,
not real-world performance; see MODEL_CARD.md.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .features import FEATURES

FRAUD_PATTERNS = ("account_takeover", "velocity_burst", "new_account_mule")


def _legit(rng: np.random.Generator, n: int) -> pd.DataFrame:
    typical = rng.lognormal(mean=6.0, sigma=0.9, size=n)                # typical spend ~R400
    ratio = rng.lognormal(mean=0.0, sigma=0.55, size=n)
    # Legitimate spikes: rent, big purchases, transfers to savings.
    spike = rng.random(n) < 0.06
    ratio[spike] *= rng.uniform(3, 15, spike.sum())
    amount = typical * ratio
    age = rng.gamma(shape=2.0, scale=2500, size=n)                        # mostly months old
    young = rng.random(n) < 0.04                                          # some genuinely new customers
    age[young] = rng.uniform(1, 72, young.sum())
    prior = np.maximum(0, (age / 24) * rng.uniform(0.2, 2.5, n)).astype(int)
    prior[young] = rng.integers(0, 4, young.sum())
    ratio[prior == 0] = 1.0                                                # no history -> neutral ratio
    tx1h = rng.poisson(0.3, n)
    busy = rng.random(n) < 0.03                                            # legit bursts (splitting a bill)
    tx1h[busy] += rng.integers(3, 9, busy.sum())
    sum24 = amount * rng.uniform(0, 3, n) + tx1h * typical
    new_cp = (rng.random(n) < np.where(prior < 5, 0.6, 0.18)).astype(int)
    drain = np.clip(rng.beta(1.3, 6.0, n), 0, 1)
    drain[spike] = np.clip(drain[spike] + rng.uniform(0.2, 0.6, spike.sum()), 0, 1)
    hours = rng.choice(24, size=n, p=_day_profile())
    night_worker = rng.random(n) < 0.05
    hours[night_worker] = rng.choice([0, 1, 2, 3, 4, 23], night_worker.sum())
    return pd.DataFrame({
        "amount": amount, "amount_to_avg_ratio": ratio, "account_age_hours": age,
        "tx_count_1h": tx1h, "amount_sum_24h": sum24, "new_counterparty": new_cp,
        "balance_drain_ratio": drain, "hour_of_day": hours, "prior_tx_count": prior,
        "pattern": "legit",
    })


def _account_takeover(rng: np.random.Generator, n: int) -> pd.DataFrame:
    typical = rng.lognormal(6.0, 0.9, n)
    ratio = rng.lognormal(np.log(8), 0.8, n)                               # usually far above normal...
    modest = rng.random(n) < 0.25                                          # ...but some attackers stay modest
    ratio[modest] = rng.lognormal(np.log(2), 0.4, modest.sum())
    age = rng.gamma(2.0, 3000, n)
    prior = np.maximum(5, (age / 24) * rng.uniform(0.3, 2.0, n)).astype(int)
    tx1h = rng.poisson(0.8, n)
    amount = typical * ratio
    return pd.DataFrame({
        "amount": amount, "amount_to_avg_ratio": ratio, "account_age_hours": age,
        "tx_count_1h": tx1h, "amount_sum_24h": amount * rng.uniform(1, 2, n),
        "new_counterparty": (rng.random(n) < 0.9).astype(int),
        "balance_drain_ratio": np.clip(rng.beta(6, 1.8, n), 0, 1),       # empties the account
        "hour_of_day": rng.choice(24, n, p=_night_profile()),
        "prior_tx_count": prior, "pattern": "account_takeover",
    })


def _velocity_burst(rng: np.random.Generator, n: int) -> pd.DataFrame:
    typical = rng.lognormal(6.0, 0.9, n)
    ratio = rng.lognormal(np.log(0.6), 0.6, n)                            # small amounts, testing the account
    age = rng.gamma(2.0, 2500, n)
    prior = np.maximum(3, (age / 24) * rng.uniform(0.3, 2.0, n)).astype(int)
    tx1h = rng.integers(6, 40, n)
    amount = typical * ratio
    return pd.DataFrame({
        "amount": amount, "amount_to_avg_ratio": ratio, "account_age_hours": age,
        "tx_count_1h": tx1h, "amount_sum_24h": amount * tx1h * rng.uniform(1, 2.5, n),
        "new_counterparty": (rng.random(n) < 0.75).astype(int),
        "balance_drain_ratio": np.clip(rng.beta(1.5, 5, n), 0, 1),
        "hour_of_day": rng.choice(24, n, p=_night_profile()),
        "prior_tx_count": prior, "pattern": "velocity_burst",
    })


def _new_account_mule(rng: np.random.Generator, n: int) -> pd.DataFrame:
    age = rng.uniform(0.2, 96, n)                                          # opened in the last few days
    prior = rng.integers(0, 6, n)
    amount = rng.lognormal(np.log(6000), 0.8, n)                           # forwards a large received sum
    return pd.DataFrame({
        "amount": amount, "amount_to_avg_ratio": np.where(prior == 0, 1.0, rng.lognormal(np.log(3), 0.7, n)),
        "account_age_hours": age, "tx_count_1h": rng.integers(1, 8, n),
        "amount_sum_24h": amount * rng.uniform(1.5, 4, n),
        "new_counterparty": (rng.random(n) < 0.95).astype(int),
        "balance_drain_ratio": np.clip(rng.beta(8, 1.5, n), 0, 1),
        "hour_of_day": rng.choice(24, n, p=_day_profile()),
        "prior_tx_count": prior, "pattern": "new_account_mule",
    })


def _day_profile() -> np.ndarray:
    p = np.array([1, 0.6, 0.4, 0.3, 0.3, 0.6, 1.5, 3, 4, 5, 5, 5.5, 6, 5.5, 5, 5, 5.5, 6, 6, 5, 4, 3, 2, 1.5])
    return p / p.sum()


def _night_profile() -> np.ndarray:
    p = np.array([5, 6, 6, 5, 4, 2, 1, 1, 1, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 2, 2, 2.5, 3, 4, 4.5])
    return p / p.sum()


def generate(n: int = 200_000, fraud_rate: float = 0.02, seed: int = 7) -> pd.DataFrame:
    """Return a shuffled frame with FEATURES, `is_fraud` and `pattern` columns."""
    rng = np.random.default_rng(seed)
    n_fraud = int(n * fraud_rate)
    shares = rng.multinomial(n_fraud, [0.45, 0.30, 0.25])
    parts = [
        _legit(rng, n - n_fraud),
        _account_takeover(rng, shares[0]),
        _velocity_burst(rng, shares[1]),
        _new_account_mule(rng, shares[2]),
    ]
    df = pd.concat(parts, ignore_index=True)
    df["amount_sum_24h"] = np.maximum(df["amount_sum_24h"], df["amount"])
    df["is_fraud"] = (df["pattern"] != "legit").astype(int)
    df = df.sample(frac=1.0, random_state=seed).reset_index(drop=True)
    return df[FEATURES + ["is_fraud", "pattern"]]

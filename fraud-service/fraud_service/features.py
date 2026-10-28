"""The feature contract between the ledger (Java) and the fraud model.

The ledger computes these from its own history at transfer time and sends them in the
scoring request. Keeping the list here, in one place, means training and serving can
never disagree about feature order or meaning.
"""

from __future__ import annotations

from pydantic import BaseModel, Field

FEATURES: list[str] = [
    "amount",                 # transfer amount, major units (e.g. rand)
    "amount_to_avg_ratio",    # amount / source account's average outgoing amount (30d); 1.0 if no history
    "account_age_hours",      # age of the source account
    "tx_count_1h",            # outgoing transfers from the source in the last hour (before this one)
    "amount_sum_24h",         # outgoing amount from the source in the last 24h INCLUDING this transfer, major units
    "new_counterparty",       # 1 if the source has never sent to this destination before
    "balance_drain_ratio",    # amount / source balance before the transfer (0..1; 0 for funding accounts)
    "hour_of_day",            # 0..23, Africa/Johannesburg time
    "prior_tx_count",         # lifetime outgoing transfers from the source
]


class TransferFeatures(BaseModel):
    """Behavioural features for one transfer. Ranges are validated so bad input fails loudly."""

    amount: float = Field(gt=0, le=1e10)
    amount_to_avg_ratio: float = Field(ge=0, le=1e6)
    account_age_hours: float = Field(ge=0, le=1e6)
    tx_count_1h: int = Field(ge=0, le=100_000)
    amount_sum_24h: float = Field(ge=0, le=1e12)
    new_counterparty: bool
    balance_drain_ratio: float = Field(ge=0, le=1)
    hour_of_day: int = Field(ge=0, le=23)
    prior_tx_count: int = Field(ge=0, le=1_000_000_000_000)

    def as_row(self) -> list[float]:
        return [float(getattr(self, name)) for name in FEATURES]

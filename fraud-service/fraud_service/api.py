"""HTTP API for the fraud scorer. Run: uvicorn fraud_service.api:app --port 8090"""

from __future__ import annotations

import hmac
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field

from .features import TransferFeatures
from .scorer import FraudScorer

log = logging.getLogger("fraud_service")
MODEL_DIR = Path(os.environ.get("FRAUD_MODEL_DIR", Path(__file__).resolve().parent.parent / "model"))
MIN_TOKEN_LENGTH = 32


def _service_token() -> str:
    token = os.environ.get("FRAUD_SERVICE_TOKEN", "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise RuntimeError(f"FRAUD_SERVICE_TOKEN must be set and at least {MIN_TOKEN_LENGTH} characters")
    return token


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.token = _service_token()          # fail fast on a missing or weak secret
    app.state.scorer = FraudScorer(MODEL_DIR)   # fail fast if the model is missing
    log.info("Loaded %s", app.state.scorer.model_version)
    yield


app = FastAPI(
    title="LedgerForge Fraud Service",
    version="1.0.0",
    description="Scores ledger transfers for fraud risk and explains each decision.",
    lifespan=lifespan,
)


def require_token(x_service_token: Annotated[str | None, Header()] = None) -> None:
    """Service-to-service auth: the ledger sends a shared secret. Constant-time comparison."""
    expected: str = app.state.token
    if x_service_token is None or not hmac.compare_digest(x_service_token.encode(), expected.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid service token")


class ScoreRequest(BaseModel):
    transfer_reference: str | None = Field(default=None, max_length=64, pattern=r"^[A-Za-z0-9._:-]*$")
    features: TransferFeatures


class ReasonOut(BaseModel):
    feature: str
    contribution: float
    message: str


class ScoreResponse(BaseModel):
    score: float
    decision: str
    reasons: list[ReasonOut]
    model_version: str


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "model_version": app.state.scorer.model_version}


@app.get("/v1/model", dependencies=[Depends(require_token)])
def model_info() -> dict:
    meta = app.state.scorer.metadata
    return {k: meta[k] for k in ("model_version", "trained_at", "features", "thresholds", "threshold_policy",
                                 "training_data", "metrics")}


@app.post("/v1/score", response_model=ScoreResponse, dependencies=[Depends(require_token)])
def score(request: ScoreRequest) -> ScoreResponse:
    result = app.state.scorer.score(request.features)
    return ScoreResponse(
        score=result.score,
        decision=result.decision.value,
        reasons=[ReasonOut(feature=r.feature, contribution=r.contribution, message=r.message) for r in result.reasons],
        model_version=result.model_version,
    )

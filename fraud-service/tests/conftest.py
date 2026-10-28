import pytest

from fraud_service.train import train

TOKEN = "test-fraud-service-token-0123456789abcdef"


@pytest.fixture(scope="session")
def model_dir(tmp_path_factory):
    out = tmp_path_factory.mktemp("model")
    train(out, n=60_000)  # smaller than production training, still enough for stable tests
    return out


@pytest.fixture(scope="session")
def client(model_dir):
    import os

    os.environ["FRAUD_SERVICE_TOKEN"] = TOKEN
    os.environ["FRAUD_MODEL_DIR"] = str(model_dir)
    import importlib

    import fraud_service.api as api

    importlib.reload(api)  # pick up FRAUD_MODEL_DIR
    from fastapi.testclient import TestClient

    with TestClient(api.app) as c:
        yield c

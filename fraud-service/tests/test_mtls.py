"""Mutual TLS: only clients holding a certificate from the trusted CA can even open a connection."""

import socket
import ssl
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import uvicorn

from fraud_service.serve import tls_options

CERTS = Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "mtls"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def mtls_url(client, monkeypatch_module):
    """Runs the real app with TLS options exactly as serve.py builds them."""
    import fraud_service.api as api

    monkeypatch_module.setenv("FRAUD_TLS_CERT", str(CERTS / "fraud-server.crt"))
    monkeypatch_module.setenv("FRAUD_TLS_KEY", str(CERTS / "fraud-server.key"))
    monkeypatch_module.setenv("FRAUD_TLS_CLIENT_CA", str(CERTS / "ca.crt"))
    port = free_port()
    server = uvicorn.Server(uvicorn.Config(api.app, host="127.0.0.1", port=port, log_level="warning",
                                           **tls_options()))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.time() + 15
    while not server.started and time.time() < deadline:
        time.sleep(0.05)
    assert server.started, "TLS server did not start"
    yield f"https://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(timeout=5)


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    yield mp
    mp.undo()


def context(client_cert: str | None) -> ssl.SSLContext:
    ctx = ssl.create_default_context(cafile=str(CERTS / "ca.crt"))
    if client_cert:
        ctx.load_cert_chain(str(CERTS / f"{client_cert}.crt"), str(CERTS / f"{client_cert}.key"))
    return ctx


def get(url: str, ctx: ssl.SSLContext) -> int:
    with urllib.request.urlopen(url + "/health", context=ctx, timeout=5) as r:
        return r.status


def test_trusted_client_certificate_is_accepted(mtls_url):
    assert get(mtls_url, context("ledger-client")) == 200


def test_no_client_certificate_is_refused(mtls_url):
    with pytest.raises((ssl.SSLError, urllib.error.URLError, ConnectionError, OSError)):
        get(mtls_url, context(None))


def test_certificate_from_another_ca_is_refused(mtls_url):
    with pytest.raises((ssl.SSLError, urllib.error.URLError, ConnectionError, OSError)):
        get(mtls_url, context("rogue-client"))


def test_client_rejects_a_server_it_does_not_trust(mtls_url):
    ctx = ssl.create_default_context(cafile=str(CERTS / "rogue-ca.crt"))
    ctx.load_cert_chain(str(CERTS / "ledger-client.crt"), str(CERTS / "ledger-client.key"))
    with pytest.raises((ssl.SSLError, urllib.error.URLError)):
        get(mtls_url, ctx)


def test_tls_without_a_client_ca_is_refused(monkeypatch):
    monkeypatch.setenv("FRAUD_TLS_CERT", "x.crt")
    monkeypatch.setenv("FRAUD_TLS_KEY", "x.key")
    monkeypatch.delenv("FRAUD_TLS_CLIENT_CA", raising=False)
    with pytest.raises(RuntimeError, match="mutual TLS"):
        tls_options()


@pytest.mark.parametrize("only", ["FRAUD_TLS_CERT", "FRAUD_TLS_KEY", "FRAUD_TLS_CLIENT_CA"])
def test_partial_tls_settings_never_fall_back_to_plain_http(monkeypatch, only):
    for name in ("FRAUD_TLS_CERT", "FRAUD_TLS_KEY", "FRAUD_TLS_CLIENT_CA"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv(only, "x")
    with pytest.raises(RuntimeError, match="mutual TLS"):
        tls_options()


def test_no_tls_settings_means_plain_http(monkeypatch):
    for name in ("FRAUD_TLS_CERT", "FRAUD_TLS_KEY", "FRAUD_TLS_CLIENT_CA"):
        monkeypatch.delenv(name, raising=False)
    assert tls_options() == {}

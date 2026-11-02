"""Starts the fraud service, with mutual TLS when certificates are configured.

    FRAUD_TLS_CERT       server certificate (PEM)
    FRAUD_TLS_KEY        server private key (PEM)
    FRAUD_TLS_CLIENT_CA  CA that signs the allowed client certificates (required whenever TLS is on)

With TLS on, a client must present a certificate signed by FRAUD_TLS_CLIENT_CA or the handshake fails,
before any HTTP request is read. The X-Service-Token check still applies on top (defence in depth).
Run: python -m fraud_service.serve
"""

from __future__ import annotations

import os
import ssl

import uvicorn


def tls_options() -> dict:
    cert = os.environ.get("FRAUD_TLS_CERT")
    if not cert:
        return {}
    key = os.environ.get("FRAUD_TLS_KEY")
    client_ca = os.environ.get("FRAUD_TLS_CLIENT_CA")
    if not key or not client_ca:
        raise RuntimeError("FRAUD_TLS_CERT is set, so FRAUD_TLS_KEY and FRAUD_TLS_CLIENT_CA are required "
                           "(TLS here always means mutual TLS)")
    return {"ssl_certfile": cert, "ssl_keyfile": key, "ssl_ca_certs": client_ca, "ssl_cert_reqs": ssl.CERT_REQUIRED}


def main() -> None:
    uvicorn.run("fraud_service.api:app", host=os.environ.get("HOST", "0.0.0.0"),
                port=int(os.environ.get("PORT", "8090")), server_header=False, **tls_options())


if __name__ == "__main__":
    main()

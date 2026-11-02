#!/bin/sh
# Creates a private CA and the certificates for mutual TLS between the ledger API and the fraud service.
# FOR DEVELOPMENT AND TESTS ONLY: production certificates come from your own PKI or a secrets manager.
#
# Usage: scripts/generate-dev-certs.sh <output-dir>
#   ROGUE=1      also create an unrelated CA with its own server/client certs (for negative tests)
#   FORCE=1      overwrite existing files
#   CERT_DAYS=N  validity (default 825)
set -eu
OUT=${1:-certs}
DAYS=${CERT_DAYS:-825}
mkdir -p "$OUT"
cd "$OUT"
if [ -f ca.crt ] && [ "${FORCE:-0}" != "1" ]; then
  echo "Certificates already exist in $OUT (set FORCE=1 to replace)."
  exit 0
fi

ca() { # name, common name
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -sha256 \
    -keyout "$1.key" -out "$1.crt" -days "$DAYS" -subj "/CN=$2" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
}

leaf() { # name, common name, signing CA, extendedKeyUsage, subjectAltName
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -sha256 \
    -keyout "$1.key" -out "$1.csr" -subj "/CN=$2" 2>/dev/null
  printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=%s\nsubjectAltName=%s\n' "$4" "$5" > "$1.ext"
  openssl x509 -req -sha256 -in "$1.csr" -CA "$3.crt" -CAkey "$3.key" -CAcreateserial \
    -out "$1.crt" -days "$DAYS" -extfile "$1.ext" 2>/dev/null
  rm -f "$1.csr" "$1.ext"
}

SERVER_SAN="DNS:fraud,DNS:localhost,IP:127.0.0.1"
ca ca "LedgerForge dev CA"
leaf fraud-server "fraud-service" ca serverAuth "$SERVER_SAN"
leaf ledger-client "ledger-api" ca clientAuth "DNS:ledger-api"

if [ "${ROGUE:-0}" = "1" ]; then
  ca rogue-ca "Untrusted CA"
  leaf rogue-server "fraud-service" rogue-ca serverAuth "$SERVER_SAN"
  leaf rogue-client "ledger-api" rogue-ca clientAuth "DNS:ledger-api"
fi
rm -f ./*.srl
echo "Wrote certificates to $(pwd)"

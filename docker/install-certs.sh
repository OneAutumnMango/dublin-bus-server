#!/usr/bin/env bash
#
# Installs any extra CA certificates dropped into certs/ into both the OS trust store
# and the JVM truststore. Needed on networks that terminate TLS (corporate proxies such
# as Zscaler); a no-op when certs/ is empty, which is the normal case.

set -euo pipefail

readonly SRC_DIR="${1:-/tmp/certs}"

shopt -s nullglob
certs=("$SRC_DIR"/*.crt "$SRC_DIR"/*.pem)
shopt -u nullglob

if (( ${#certs[@]} == 0 )); then
  echo "No extra CA certificates supplied; using the default trust store."
  exit 0
fi

echo "Installing ${#certs[@]} extra CA certificate(s)"

mkdir -p /usr/local/share/ca-certificates
for cert in "${certs[@]}"; do
  install -m 0644 "$cert" "/usr/local/share/ca-certificates/$(basename "${cert%.*}").crt"
done
update-ca-certificates

# The JVM keeps its own truststore, so the OS store alone is not enough.
if command -v keytool >/dev/null 2>&1; then
  for cert in "${certs[@]}"; do
    keytool -importcert -noprompt -trustcacerts \
      -alias "extra-$(basename "${cert%.*}")" \
      -file "$cert" -cacerts -storepass changeit >/dev/null 2>&1 || true
  done
  echo "Imported extra CA certificates into the JVM truststore."
fi

#!/usr/bin/env bash
#
# Runs the arrivals server, loading config from server.env (gitignored; copy from
# server.env.example) or from already-exported environment variables.
#
#   ./scripts/run-server.sh            # run in the foreground via Gradle
#   ./scripts/run-server.sh --build    # build a standalone distribution instead

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="$REPO_ROOT/server.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

: "${SERVER_API_KEY:?set SERVER_API_KEY in server.env or the environment}"
: "${GTFSR_API_KEY:?set GTFSR_API_KEY in server.env or the environment}"

cd "$REPO_ROOT"

if [[ "${1:-}" == "--build" ]]; then
  ./gradlew --console=plain installDist
  echo "Standalone build at build/install/dublin-bus-server/bin/dublin-bus-server"
else
  ./gradlew --console=plain run
fi

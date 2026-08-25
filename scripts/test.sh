#!/usr/bin/env bash
#
# Runs the server's unit tests.
#
#   ./scripts/test.sh
#   ./scripts/test.sh --filter Routes

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

args=(--console=plain test)
if [[ "${1:-}" == "--filter" ]]; then
  args+=(--tests "*${2:?--filter needs a pattern}*")
fi

./gradlew "${args[@]}"

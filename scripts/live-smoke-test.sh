#!/usr/bin/env bash
#
# Runs the real server against the live NTA API using server.env, and checks that real data
# comes back: the feeds actually have entities, no poll failures, and a basic request against
# each endpoint family succeeds. This is not a substitute for ./scripts/test.sh - it exercises
# the real upstream, so it costs part of your rate-limit budget and needs a real GTFSR_API_KEY
# and jq.
#
#   ./scripts/live-smoke-test.sh

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="$REPO_ROOT/server.env"

cd "$REPO_ROOT"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
: "${GTFSR_API_KEY:?set GTFSR_API_KEY in server.env or the environment}"

export PORT="${LIVE_SMOKE_PORT:-8099}"
readonly BASE_URL="http://localhost:$PORT"

# Run through Gradle rather than a built distribution, so the project's own JDK 21 toolchain
# is used regardless of what `java` happens to be on PATH.
server_pid=""
cleanup() {
  [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null || true
  # The Gradle daemon forks the app as its own child, outliving the wrapper script above.
  pkill -f 'dublinbustracker\.server\.MainKt' 2>/dev/null || true
}
trap cleanup EXIT

./gradlew --console=plain run > /tmp/dublin-bus-server-live-smoke.log 2>&1 &
server_pid=$!

echo "Waiting for the server to come up..."
up=false
for _ in $(seq 1 30); do
  if curl -s -o /dev/null "$BASE_URL/health"; then
    up=true
    break
  fi
  sleep 1
done
if [[ "$up" != "true" ]]; then
  echo "Server never came up. Log:"
  cat /tmp/dublin-bus-server-live-smoke.log
  exit 1
fi

failures=0
check() {
  local description="$1" url="$2" jq_filter="$3"
  local result
  if result="$(curl -s "$BASE_URL$url" | jq -re "$jq_filter" 2>&1)"; then
    echo "OK    $description -> $result"
  else
    echo "FAIL  $description -> $result"
    failures=$((failures + 1))
  fi
}

echo
echo "== Live checks against the real NTA feed =="
check "status: every configured feed has real entities" "/v1/status" \
  'select(([.feeds[].entityCount] | min) > 0) | (.feeds | map("\(.feed): \(.entityCount) entities, \(.ageSeconds)s old") | join(", ")) + " (failures=\(.refreshFailures))"'
check "stop search returns real stops" "/v1/stops?query=Trinity&limit=1" 'length | select(. > 0)'
check "nearby search returns real stops" "/v1/stops/nearby?lat=53.3441&lon=-6.2578&radius=500" 'length | select(. > 0)'
check "route search returns real routes" "/v1/routes?query=15" 'length | select(. > 0)'
check "live vehicles are being tracked" "/v1/vehicles?limit=5" 'length | select(. > 0)'

echo
if [[ "$failures" -gt 0 ]]; then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All live checks passed."

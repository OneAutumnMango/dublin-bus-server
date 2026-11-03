#!/usr/bin/env bash
#
# Downloads the static TFI/NTA GTFS feed and filters it down to the stops served by
# the operators covered by the GTFS-Realtime API (Dublin Bus, Bus Éireann, Go-Ahead
# Ireland), writing a compact JSON resource the server bundles and reads at startup.
#
#   ./scripts/fetch-gtfs-data.sh          # fetch + filter (re-run to refresh)
#   ./scripts/fetch-gtfs-data.sh --check  # report whether the resource is present
#   ./scripts/fetch-gtfs-data.sh --no-shapes    # skip route geometry (much smaller bundle)
#   ./scripts/fetch-gtfs-data.sh --no-patterns  # skip per-route stop sequences
#
# No API key needed - this is the free static GTFS zip.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly GTFS_URL="https://www.transportforireland.ie/transitData/Data/GTFS_Realtime.zip"
OUT_FILE="${GTFS_STATIC_PATH:-$REPO_ROOT/src/main/resources/gtfs_static.json}"
readonly AGENCY_NAME_PATTERN="${GTFS_AGENCY_PATTERN:-Dublin Bus|Bus.?Éireann|Bus.?Eireann|Go.?Ahead}"

CHECK_ONLY=0
FILTER_FLAGS=()

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_ERR=$'\033[31m'
else
  C_RESET=''; C_INFO=''; C_OK=''; C_ERR=''
fi

info() { printf '%s==>%s %s\n' "$C_INFO" "$C_RESET" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$C_OK" "$C_RESET" "$*"; }
die()  { printf '%s err%s %s\n' "$C_ERR" "$C_RESET" "$*" >&2; exit 1; }

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check)        CHECK_ONLY=1 ;;
    --no-shapes)    FILTER_FLAGS+=(--no-shapes) ;;
    --no-patterns)  FILTER_FLAGS+=(--no-patterns) ;;
    --out)          OUT_FILE="${2:?--out needs a path}"; shift ;;
    -h|--help)      usage ;;
    *)              die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

if (( CHECK_ONLY )); then
  [[ -s "$OUT_FILE" ]] && ok "$(python3 -c "import json;print(len(json.load(open('$OUT_FILE'))['stops']))" 2>/dev/null || echo '?') stops in $OUT_FILE" \
    || { echo "missing: $OUT_FILE"; exit 1; }
  exit 0
fi

command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

info "Downloading static GTFS"
curl -fsSL --progress-bar -o "$TMP_DIR/gtfs.zip" "$GTFS_URL"

info "Extracting"
unzip -q -o "$TMP_DIR/gtfs.zip" -d "$TMP_DIR/gtfs" \
  agency.txt routes.txt trips.txt stop_times.txt stops.txt shapes.txt \
  calendar.txt calendar_dates.txt

info "Filtering to $AGENCY_NAME_PATTERN (this reads ~700MB, give it a minute)"
mkdir -p "$(dirname "$OUT_FILE")"
python3 "$REPO_ROOT/scripts/filter_gtfs.py" \
  "$TMP_DIR/gtfs" "$OUT_FILE" "$AGENCY_NAME_PATTERN" "${FILTER_FLAGS[@]+"${FILTER_FLAGS[@]}"}"

count="$(python3 -c "import json;print(len(json.load(open('$OUT_FILE'))['stops']))")"
(( count > 0 )) || die "filter produced no stops - check AGENCY_NAME_PATTERN against $TMP_DIR/gtfs/agency.txt"
ok "wrote $(du -h "$OUT_FILE" | cut -f1) to $OUT_FILE"

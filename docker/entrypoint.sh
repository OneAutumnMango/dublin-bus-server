#!/usr/bin/env bash
#
# Generates the static GTFS bundle on first run, then hands off to the server.
# The bundle lives on the /data volume so it survives container replacement and is
# only rebuilt when you delete it (or run with REFRESH_GTFS_ON_START=true).

set -euo pipefail

readonly BUNDLE="${GTFS_STATIC_PATH:-/data/gtfs_static.json}"

if [[ "${REFRESH_GTFS_ON_START:-false}" == "true" ]]; then
  echo "REFRESH_GTFS_ON_START=true - rebuilding $BUNDLE"
  rm -f "$BUNDLE"
fi

if [[ ! -s "$BUNDLE" ]]; then
  echo "No static GTFS bundle at $BUNDLE - downloading and filtering (a few minutes, one time)"
  GTFS_STATIC_PATH="$BUNDLE" /app/scripts/fetch-gtfs-data.sh
fi

exec /app/bin/dublin-bus-server "$@"

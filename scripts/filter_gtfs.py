"""Filters a static GTFS extract down to the agencies matching a regex and writes a
compact JSON bundle the server loads at startup.

Emitted bundle (schema version 2):
  stops         [{id, code, name, lat, lon}]
  routes        [{id, shortName, longName, agencyId}]
  trips         {tripId: {routeId, headsign, directionId, shapeId}}
  shapes        {shapeId: googleEncodedPolyline}
  routePatterns {routeId: {directionId: [stopId, ...]}}
  stopRoutes    {stopId: [routeShortName, ...]}

shapes.txt and stop_times.txt are hundreds of MB, so shapes are deduplicated and
polyline-encoded, and stop patterns are reduced to one representative trip per
route/direction rather than retaining every trip's stop times.
"""
import argparse
import csv
import json
import re
import sys
from collections import defaultdict

# Some stop_times/shapes rows exceed the default field limit on certain platforms.
csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

# Cap on trips examined per route/direction when picking a representative stop pattern.
CANDIDATES_PER_PATTERN = 30


def read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def stream_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        yield from csv.DictReader(f)


def encode_polyline(points, precision=5):
    """Google encoded polyline, as consumed by the route/trip shape endpoints."""
    factor = 10**precision
    result = []
    prev_lat = prev_lon = 0
    for lat, lon in points:
        lat_i = int(round(lat * factor))
        lon_i = int(round(lon * factor))
        for delta in (lat_i - prev_lat, lon_i - prev_lon):
            delta = ~(delta << 1) if delta < 0 else (delta << 1)
            while delta >= 0x20:
                result.append(chr((0x20 | (delta & 0x1F)) + 63))
                delta >>= 5
            result.append(chr(delta + 63))
        prev_lat, prev_lon = lat_i, lon_i
    return "".join(result)


def select_agencies(gtfs_dir, pattern):
    agencies = [r for r in read_csv(f"{gtfs_dir}/agency.txt") if pattern.search(r["agency_name"])]
    if not agencies:
        raise SystemExit(f"no agency matched {pattern.pattern!r} - check agency.txt")
    return {r["agency_id"] for r in agencies}


def scan_stop_times(gtfs_dir, trip_route, trip_direction, route_short_names, candidates):
    """Single pass over the largest file, collecting everything that depends on it."""
    served_stop_ids = set()
    stop_routes = defaultdict(set)
    candidate_sequences = defaultdict(list)

    for row in stream_csv(f"{gtfs_dir}/stop_times.txt"):
        trip_id = row["trip_id"]
        route_id = trip_route.get(trip_id)
        if route_id is None:
            continue
        stop_id = row["stop_id"]
        served_stop_ids.add(stop_id)
        short_name = route_short_names.get(route_id)
        if short_name:
            stop_routes[stop_id].add(short_name)
        if trip_id in candidates:
            candidate_sequences[trip_id].append((int(row["stop_sequence"]), stop_id))

    patterns = defaultdict(dict)
    for trip_id, stops in candidate_sequences.items():
        route_id = trip_route[trip_id]
        direction = trip_direction.get(trip_id, "0")
        ordered = [stop_id for _, stop_id in sorted(stops)]
        existing = patterns[route_id].get(direction)
        # The longest variant is the best single answer to "what stops does this route serve".
        if existing is None or len(ordered) > len(existing):
            patterns[route_id][direction] = ordered

    return served_stop_ids, stop_routes, patterns


def collect_shapes(gtfs_dir, wanted_shape_ids):
    points = defaultdict(list)
    for row in stream_csv(f"{gtfs_dir}/shapes.txt"):
        shape_id = row["shape_id"]
        if shape_id not in wanted_shape_ids:
            continue
        points[shape_id].append(
            (int(row["shape_pt_sequence"]), float(row["shape_pt_lat"]), float(row["shape_pt_lon"]))
        )
    return {
        shape_id: encode_polyline([(lat, lon) for _, lat, lon in sorted(rows)])
        for shape_id, rows in points.items()
    }


def main():
    parser = argparse.ArgumentParser(description="Filter a GTFS extract into the server bundle.")
    parser.add_argument("gtfs_dir")
    parser.add_argument("out_file")
    parser.add_argument("agency_pattern")
    parser.add_argument("--no-shapes", action="store_true", help="skip route geometry (smaller bundle)")
    parser.add_argument("--no-patterns", action="store_true", help="skip per-route stop sequences")
    args = parser.parse_args()

    pattern = re.compile(args.agency_pattern, re.IGNORECASE)
    agency_ids = select_agencies(args.gtfs_dir, pattern)

    routes = [r for r in read_csv(f"{args.gtfs_dir}/routes.txt") if r["agency_id"] in agency_ids]
    route_ids = {r["route_id"] for r in routes}
    route_short_names = {r["route_id"]: r.get("route_short_name") or r["route_id"] for r in routes}

    trips = [r for r in read_csv(f"{args.gtfs_dir}/trips.txt") if r["route_id"] in route_ids]
    trip_route = {r["trip_id"]: r["route_id"] for r in trips}
    trip_direction = {r["trip_id"]: r.get("direction_id") or "0" for r in trips}

    candidates = set()
    if not args.no_patterns:
        grouped = defaultdict(list)
        for row in trips:
            grouped[(row["route_id"], row.get("direction_id") or "0")].append(row["trip_id"])
        for trip_ids in grouped.values():
            candidates.update(sorted(trip_ids)[:CANDIDATES_PER_PATTERN])

    served_stop_ids, stop_routes, patterns = scan_stop_times(
        args.gtfs_dir, trip_route, trip_direction, route_short_names, candidates
    )

    stops = sorted(
        (
            {
                "id": row["stop_id"],
                "code": row.get("stop_code") or row["stop_id"],
                "name": row["stop_name"],
                "lat": float(row["stop_lat"]),
                "lon": float(row["stop_lon"]),
            }
            for row in read_csv(f"{args.gtfs_dir}/stops.txt")
            if row["stop_id"] in served_stop_ids
        ),
        key=lambda s: s["name"],
    )

    shapes = {}
    if not args.no_shapes:
        shapes = collect_shapes(args.gtfs_dir, {r["shape_id"] for r in trips if r.get("shape_id")})

    bundle = {
        "version": 2,
        "stops": stops,
        "routes": [
            {
                "id": r["route_id"],
                "shortName": route_short_names[r["route_id"]],
                "longName": r.get("route_long_name", ""),
                "agencyId": r["agency_id"],
            }
            for r in sorted(routes, key=lambda r: route_short_names[r["route_id"]])
        ],
        "trips": {
            r["trip_id"]: {
                "routeId": r["route_id"],
                "headsign": r.get("trip_headsign", ""),
                "directionId": r.get("direction_id") or "0",
                "shapeId": r.get("shape_id") or "",
            }
            for r in trips
        },
        "shapes": shapes,
        "routePatterns": {route_id: dict(dirs) for route_id, dirs in patterns.items()},
        "stopRoutes": {stop_id: sorted(names) for stop_id, names in stop_routes.items()},
    }

    with open(args.out_file, "w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, separators=(",", ":"))

    print(
        f"stops={len(stops)} routes={len(bundle['routes'])} trips={len(bundle['trips'])} "
        f"shapes={len(shapes)} patterns={len(bundle['routePatterns'])}"
    )


if __name__ == "__main__":
    main()

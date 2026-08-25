"""Filters a static GTFS extract down to the agencies matching a regex, writing a
compact JSON asset for the :data module: stops (for search/map), and a routeId/tripId
lookup (for labelling realtime arrivals with a route short name and destination).
"""
import csv
import json
import re
import sys


def read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def main():
    gtfs_dir, out_file, agency_pattern = sys.argv[1], sys.argv[2], sys.argv[3]
    pattern = re.compile(agency_pattern, re.IGNORECASE)

    agency_ids = {
        row["agency_id"] for row in read_csv(f"{gtfs_dir}/agency.txt")
        if pattern.search(row["agency_name"])
    }
    routes = [
        row for row in read_csv(f"{gtfs_dir}/routes.txt")
        if row["agency_id"] in agency_ids
    ]
    route_ids = {row["route_id"] for row in routes}

    trips = [
        row for row in read_csv(f"{gtfs_dir}/trips.txt")
        if row["route_id"] in route_ids
    ]
    trip_ids = {row["trip_id"] for row in trips}

    stop_ids = set()
    with open(f"{gtfs_dir}/stop_times.txt", newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row["trip_id"] in trip_ids:
                stop_ids.add(row["stop_id"])

    stops = [
        {
            "id": row["stop_id"],
            "code": row.get("stop_code") or row["stop_id"],
            "name": row["stop_name"],
            "lat": float(row["stop_lat"]),
            "lon": float(row["stop_lon"]),
        }
        for row in read_csv(f"{gtfs_dir}/stops.txt")
        if row["stop_id"] in stop_ids
    ]
    stops.sort(key=lambda s: s["name"])

    route_short_names = {row["route_id"]: row["route_short_name"] for row in routes}
    trip_info = {
        row["trip_id"]: {"routeId": row["route_id"], "headsign": row.get("trip_headsign", "")}
        for row in trips
    }

    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(
            {"stops": stops, "routes": route_short_names, "trips": trip_info},
            f,
            ensure_ascii=False,
            separators=(",", ":"),
        )


if __name__ == "__main__":
    main()


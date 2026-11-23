# Future work: DART / train positions on the map

Status: not started. Noted 2026-11-23. The user asked to be reminded of this until it is done or dropped.

## Why it is not there yet
NTA's GTFS-Realtime vehicles feed carries buses only, so Luas and Irish Rail rows have no GPS
(`hasGps` is always false for `mode` tram/rail). Rail/tram arrivals come from trip updates plus the
static timetable (see the Luas/Irish Rail bundle change).

## What is known
- Irish Rail's own realtime API (https://api.irishrail.ie/realtime/, XML, no key) documents
  `TrainLatitude` / `TrainLongitude`, `TrainCode`, `Direction`, `TrainStatus` per train. Not yet verified live,
  and it is one request per station, so polling cost and any rate limit need checking first.
- The Luas forecasting API (data.gov.ie) gives arrival times per stop and, as far as found, no tram positions.
  A Luas map is probably not possible.

## Questions for whoever picks this up
1. Does the Irish Rail endpoint still work, and what format/rate limits does it have?
2. Poll which stations (DART only?) and how often; cache so one poll serves all clients, like the bus feeds.
3. Join to NTA trips (Irish Rail trip ids are `6065_*`; `TrainCode` such as `E206` matches static `trip_short_name`).
4. Where positions surface: a new source feeding `/v1/vehicles` with `mode`, then an app map layer.

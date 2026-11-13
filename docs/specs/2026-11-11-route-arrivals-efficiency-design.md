# Route arrivals efficiency (server) + analytics timeout/concurrency

Status: approved design, 2026-11-11. Server work branches from `feat/scheduled-epoch`. The analytics part lands in the `dublin-bus-analytics` repo.

## Problem
On the Raspberry Pi, `GET /v1/routes/{route}/arrivals` takes 6-15 s (staircase under concurrency). `dublin-bus-analytics` has a 10 s read timeout, so slow responses become `ReadTimeout` -> "server unavailable" -> recorded gaps. The server still finishes and logs `200 OK`, doing wasted work.

Cause: `Routes.kt` calls `ArrivalsService.arrivalsForStop()` once per stop on the route (50-100 stops). Each call rescans the whole Dublin feed (all trip updates: suppression scan, `firstReport` for every trip) and only then filters to the route.

## Scope
- In: route arrivals endpoint, a response cache, analytics client timeout and poll concurrency, tests, a before/after timing.
- Non-goals: per-stop feed index, changing `/v1/stops/*/arrivals` behaviour, JSON shape changes, moving work off the Ktor event loop, releasing images.

## Server design
### 1. Route pre-filter
Add `ArrivalsService.arrivalsForRoute(identifier, stopIds, limit): Map<String, List<Arrival>>`.
- Computes `now` and the full update list once, then the route's subset (a trip belongs to the route when `trip?.routeId ?: update.trip.routeId` is in the resolved route ids).
- Equivalence: the existing per-stop logic already discards other routes' trips at every stage (suppression, `liveTripIds`, direct, cascaded, scheduled), so restricting the input to the route's trips gives identical output.
- `arrivalsForStop` and `arrivalsForRoute` share one private core that takes a pre-filtered update list, so arrival logic exists once. `arrivalsForStop` stays the reference implementation.
- `Routes.kt` route handler calls `arrivalsForRoute` once; stops with no arrivals are still omitted.

### 2. Response cache
- Key: identifier as given + limit.
- Entry valid while: TRIP_UPDATES and COMBINED snapshot `fetchedAtEpochMs` are unchanged, `staticData.current` is the same instance, and age <= 15 s.
- Single-flight: concurrent requests for a key share one computation.
- Bound: max 256 entries; cleared on a new snapshot.

## Analytics design (dublin-bus-analytics)
- `SERVER_TIMEOUT_SECONDS` (default 45) replaces the hardcoded read timeout in `serverclient.py`; connect timeout stays 5 s.
- `SERVER_CONCURRENCY` (default 2): semaphore limiting concurrent route fetches in `Poller.tick`.
- Gap semantics unchanged; the poller already drops missed slots rather than bursting.

## Testing
- Differential test: `arrivalsForRoute` == per-stop `arrivalsForStop` on feeds with cancelled trips, skipped stops, loop trips, trips with no static route, scheduled-only trips.
- Cache tests: hit, new snapshot, TTL expiry, static data swap, single-flight under concurrency, entry cap.
- Analytics: timeout configurable; concurrency never exceeds the limit.
- Timing: synthetic Dublin-sized feed (~3000 trip updates, 80-stop route) before/after, numbers recorded in the commit message. Not asserted in CI. Measure first: if `scheduledArrivalsForStop` (static scan per stop) dominates, scan once per route inside the route scope.

## Failure modes / limitations
- Static timetable scans may dominate; handled by measure-first step above.
- Equivalence break on odd trips (static vs feed route mismatch): covered by the differential test.
- Cached responses are up to 15 s stale (`dueMinutes`, `possiblyPassed`). Analytics stores rows only on `(expected, realtime)` change, so stale data cannot create spurious samples; "due <= 0" detection may lag up to 15 s. Accepted.
- First request after a snapshot is still a (much faster) miss.

## Rollout
Rebuild and tag new `dublin-bus-server` and `dublin-bus-analytics` images yourself, then update the compose tags. Old and new versions are wire-compatible.

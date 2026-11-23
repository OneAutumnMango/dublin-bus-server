# Bundle health detection and cancellations

Date: 2026-11-23. Repos: dublin-bus-server (A, B server), dublin-bus (B app), dublin-bus-analytics (B analytics).
Rail (DART/Irish Rail, Luas) is a separate later spec (C).

## Problem

1. The static GTFS bundle goes stale: NTA changed Dublin Bus ids (routes `1 4 c a` -> `1_4_c_a`, trip prefix
   `6036_` -> `6056_`), so stop 414 (routes 4, 7, 7A, S2, 27X) showed only scheduled times. On the Pi, 83.4% of
   live vehicles (928/1113) had no matching trip; operators 6056 and 6066 were 100% unmatched, 6018 and 6026 0%.
   The 24 h refresh never ran because its timer restarted with every process start.
2. The server hides cancelled trips and skipped stops (`ArrivalsService.kt`) but never reports them, so the app and
   analytics cannot show or track cancellations.

## A. Detect a bad bundle (dublin-bus-server)

Already done: first refresh waits only for the bundle file's remaining age (commit f7b1e91).

- After each TRIP_UPDATES poll, count trip updates whose `tripId` is not in the bundle (`staticData.current.trip`).
  Updates with schedule relationship ADDED/NEW are excluded (not in static data by design).
- 5 consecutive polls with unmatched > 0 triggers `StaticDataRefresher.refresh()`, at most once per 6 h
  (cooldown counts any attempt, including the timer's). The refresh is launched in the refresher's own scope
  so polling is never blocked, and only one refresh runs at a time.
- `FeedPoller` gets an optional per-feed callback; the refresher's `observe(feed)` is wired in `AppServices`.
- `/v1/status` gains `unmatchedTripUpdates` and `lastStaticRefreshEpochMs`. WARN log on trigger; the existing ntfy
  warning if a later streak still trips after a refresh.
- Thresholds are named constants (streak 5, min unmatched 1, cooldown 6 h). Tune after measuring the unmatched
  count on a good bundle (run `scratchpad/mm.py`-style check after the Pi rebuild).
- Test: fake feed + stub script; streak, reset on a clean poll, cooldown, ADDED exclusion, no overlap.

## B. Cancellations

Order inside B: counters, API flag, app, analytics.

1. **Counters (server).** Per poll, count CANCELED trips, SKIPPED stop-time updates and alerts; show in `/v1/status`.
   Run for a day before building the rest. If NTA never sends CANCELED/SKIPPED, B reduces to alerts only.
2. **API flag (server).** Arrivals gain `cancelled: Boolean` (default false), keeping the scheduled epoch from the
   timetable. Cancelled and skipped arrivals are returned only with `?includeCancelled=true`; the default
   response is unchanged, so installed apps never show a cancelled bus as live.
3. **App (dublin-bus).** Opts in; renders a greyed, struck-through row labelled "Cancelled".
4. **Analytics (dublin-bus-analytics).** Polls with `includeCancelled=true`. New table of cancelled/skipped trips
   keyed (trip_id, stop_id, scheduled_epoch) with first/last seen, kind, route; a trip that later reappears
   uncancelled updates its row. Alerts stored with id, text, validity period, routes, stops, first/last seen.
   API and UI show a cancellation rate beside delay stats. Before implementing, read how backup, monthly archive,
   restore and the result cache treat tables, and follow them for the new tables.

## Non-goals / known limits

- A cancelled trip that leaves the feed before it is polled is never recorded.
- A only detects id mismatches, not other kinds of wrong bundle (e.g. wrong times).
- No rail, no change to the default (non-opt-in) API behaviour.

## Failure modes considered

- Critical, handled: old app would display cancelled buses as live -> opt-in flag.
- Refresh loop if NTA publishes realtime before static -> 6 h cooldown plus ntfy.
- Pi memory spike during refresh (two bundles) -> cooldown; watch on first Pi run.

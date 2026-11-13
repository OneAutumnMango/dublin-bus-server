# Arrival GPS and "started" indicators — design

Spans two repos: `dublin-bus-server` (flags) and `dublin-bus` (Android app, stop view).

## Goal
In the stop view, each live arrival row shows a small GPS icon when the bus has a live GPS position, and a
"started its journey" icon once the bus has set off from its first stop. Nothing is shown for timetable-only
arrivals, and nothing says "timetable only". Widget and map are unchanged.

## Definitions (decided with the user)
- **hasGps**: the VEHICLES feed has a position for the arrival's trip, and that fix is under 3 minutes old
  (a parked bus's 10-15 minute old fix does not count).
- **started**: the trip's live report has moved past its first stop, i.e. the stop the server treats as the
  trip's current report (`firstReport(now)`: first stop-time update not older than 2 minutes, else the last)
  has `stop_sequence > 1`. This is the same rule that already removes a bus from its first stop's arrivals list.
  Distance from the terminus is deliberately NOT used (user's choice).
- Timetable-only arrivals (`isRealtime = false`): both false.

## Evidence (live server, 50 buses, 2026-11-13)
Using this rule 45/50 trips are "started", the 5 that are not are all near the terminus (136-180 m), no bus
far from its first stop is classed not-started. The raw "seq 1 still listed" check would have missed 60%, which
is why `firstReport` is used, not "first stop absent from the feed".

## Server changes
- `Arrival` / `ArrivalResponse`: add `hasGps: Boolean = false`, `started: Boolean = false`.
- `started` computed where `firstReport` is already called (both arrival builders); `firstReport` also yields the
  `StopTimeUpdate.stopSequence`.
- `hasGps` from a set of trip ids with a fresh vehicle fix, built once per VEHICLES snapshot (not per stop; the
  route-arrivals path loops over every stop).

## App changes
- `core` `Arrival`: add `hasGps`, `started` (default false); `TransitApiClient` DTO parses them (missing = false,
  so an old server works).
- `ArrivalDisplay`: carry both flags; `StopDetailScreen` draws a GPS icon and a "started" icon (content
  descriptions "GPS position known", "Started its journey") on the row. Muted ("may have left") rows keep the
  existing styling.

## Non-goals
Widget/map/bus-info-sheet indicators; distance-based detection; changing "may have left".

## Failure modes
- A bus late to leave its terminus is marked started once the feed's report moves past stop 1 (2 of 31 sampled,
  9 m and 176 m away). Accepted; follows from the chosen rule.
- Feed without `stop_sequence` gives `started = false` (safe default: no icon).
- GPS flag can lag the real position by the ~1 minute poll interval.
- Server must be redeployed on the Pi; until then the app simply shows no icons.

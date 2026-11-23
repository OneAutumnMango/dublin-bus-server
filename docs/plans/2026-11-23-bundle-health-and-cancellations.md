# Bundle Health and Cancellations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:subagent-driven-development (recommended) or superpowers-optimized:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the server refresh a bad static bundle on its own, and expose, show and track cancelled buses.
**Architecture:** `StaticDataRefresher` observes each TRIP_UPDATES poll and refreshes (in its own coroutine, with a cooldown) after a streak of polls containing trips the bundle doesn't know. Cancelled/skipped arrivals are returned by the server only when a client opts in with `includeCancelled=true`; the app and analytics opt in.
**Tech Stack:** Kotlin/Ktor (dublin-bus-server), Kotlin/Compose (dublin-bus), Python/FastAPI/SQLite (dublin-bus-analytics).
**Spec:** `docs/specs/2026-11-23-bundle-health-and-cancellations-design.md`
**Assumptions:** Assumes NTA's feed marks cancellations with `CANCELED` / `SKIPPED` — will NOT produce cancelled rows if NTA only uses alerts (Task 3 measures this before Tasks 5-8 are built). Assumes a healthy bundle has ~0 unmatched trips — will NOT be reliable if the baseline measured in Task 9 is higher (then raise `MIN_UNMATCHED`).

## Global Constraints

- Default API responses must not change: cancelled/skipped arrivals appear only with `?includeCancelled=true`.
- Detector constants: streak 5 polls, min unmatched 1, cooldown 6 h (named constants, tunable).
- Updates with schedule relationship ADDED or NEW are never counted as unmatched.
- Polling must never block on a refresh.
- Memory on the Pi is tight: max one refresh at a time.
- Commit per task, on a branch (not main). Commit messages end with the attribution line from the session.

## Review Focus

- Feed with only ADDED trips (not in static data): must not trigger a refresh.
- A clean poll mid-streak resets the streak.
- Refresh already running (timer or earlier trigger): a second trigger is ignored.
- `/routes/{route}/arrivals` response cache: a request with `includeCancelled=true` must not be served a cached response made without it (and vice versa).
- A cancelled trip must never appear in default-mode responses (the old app).

---

## File Structure

- `dublin-bus-server/src/main/kotlin/dev/dublinbustracker/server/StaticDataRefresher.kt` — add `observe`, streak, cooldown, single-flight.
- `.../server/FeedPoller.kt` — optional per-feed callback.
- `.../server/AppServices.kt` — wire callback; `.../server/Routes.kt`, `.../server/Dto.kt` — status fields, `includeCancelled`, `cancelled`.
- `.../core/Models.kt` (`Arrival`) and `.../server/ArrivalsService.kt` — cancelled arrivals.
- `dublin-bus/...` and `dublin-bus-analytics/...` — clients (Tasks 6-8).

### Task 1: Refresher observes polls and refreshes on a bad-bundle streak

**Files:**
- Modify: `dublin-bus-server/src/main/kotlin/dev/dublinbustracker/server/StaticDataRefresher.kt`
- Test: `dublin-bus-server/src/test/kotlin/dev/dublinbustracker/server/StaticDataRefresherTest.kt`

**Interfaces:**
- Consumes: `StaticDataRef.current.trip(tripId): Trip?`; existing `suspend fun refresh()`; `start(scope: CoroutineScope)`.
- Produces: `fun observe(feed: FeedMessage)` (non-blocking); `val unmatchedTripUpdates: Int` (count from the latest observed feed); `val lastRefreshEpochMs: Long` (0 if never); constructor params `nowMs: () -> Long = System::currentTimeMillis`, `streakPolls: Int = 5`, `minUnmatched: Int = 1`, `cooldownMs: Long = 6 * 3_600_000L`.

**Security flag:** `none`

**Does NOT cover:** bundles wrong in ways other than trip-id mismatch; the stale-by-age check (already committed, f7b1e91); refreshes before `start(scope)` was called (observe is a no-op until then).

- [ ] **Step 1: Write failing tests** (names and assertions)
  - `observe launches a refresh after five consecutive polls with unmatched trips` — fake feed with 3 unknown trip ids, 5 calls, stub script copies fixture; assert target bundle swapped.
  - `a clean poll resets the streak` — 4 bad, 1 clean, 4 bad: assert script not run.
  - `ADDED trips are not counted as unmatched` — 5 polls of ADDED-only trips: not run.
  - `cooldown blocks a second refresh within six hours` — injected `nowMs`; second streak at +1 h: script run count stays 1; at +7 h: 2.
  - `a running refresh is not started twice` — slow script, trigger twice, run count 1.
- [ ] **Step 2: Run** `./gradlew test --tests '*StaticDataRefresherTest*' --offline` — Expected: FAIL (unresolved `observe`).
- [ ] **Step 3: Implement** the signatures above in `StaticDataRefresher.kt`. Streak counter and an `AtomicBoolean` running flag; launch `refresh()` on the scope saved by `start()`; set `lastRefreshEpochMs` when an attempt starts (the timer loop's `refresh()` should set it too, so the cooldown counts every attempt); log WARN on trigger.
- [ ] **Step 4: Run** the same command — Expected: PASS (existing 6 tests too).
- [ ] **Step 5: Commit** `git add` both files; message "Refresh the static bundle after a streak of unmatched live trips".

### Task 2: Wire the poller to the refresher and show it in /v1/status

**Files:**
- Modify: `.../server/FeedPoller.kt`, `.../server/AppServices.kt`, `.../server/Dto.kt` (`StatusResponse`), `.../server/Routes.kt` (`buildStatus`)
- Test: `.../server/FeedPollerTest.kt`, `.../server/RoutesTest.kt`

**Interfaces:**
- Consumes: Task 1's `observe`, `unmatchedTripUpdates`, `lastRefreshEpochMs`.
- Produces: `FeedPoller(..., onFeed: (FeedKind, FeedMessage) -> Unit = { _, _ -> })` called after `store.record`; `StatusResponse.unmatchedTripUpdates: Int`, `StatusResponse.lastStaticRefreshEpochMs: Long`.

**Security flag:** `none`

**Does NOT cover:** persisting the counters across restarts.

- [ ] **Step 1: Tests** — `FeedPollerTest`: `onFeed is called with the kind and message after a successful fetch` and `onFeed is not called when the fetch fails`. `RoutesTest`: `status includes unmatchedTripUpdates and lastStaticRefreshEpochMs`.
- [ ] **Step 2: Run** `./gradlew test --tests '*FeedPollerTest*' --tests '*RoutesTest*' --offline` — Expected: FAIL.
- [ ] **Step 3: Implement.** In `AppServices.build`, construct the refresher first and pass `onFeed = { kind, feed -> if (kind == FeedKind.TRIP_UPDATES) refresher.observe(feed) }` to `FeedPoller`; add the two status fields in `buildStatus`.
- [ ] **Step 4: Run** the full suite `./gradlew test --offline` — Expected: PASS except the known timing-only failures (compare to main).
- [ ] **Step 5: Commit** "Wire poller to the bundle-health check; expose it in /v1/status".

### Task 3: Count cancelled trips, skipped stops and alerts per poll

**Files:**
- Modify: `.../server/ArrivalsService.kt` (or a new `FeedCounters.kt` beside it), `.../server/Dto.kt`, `.../server/Routes.kt`
- Test: `.../server/ArrivalsServiceTest.kt`

**Interfaces:**
- Consumes: Task 2's `StatusResponse` shape.
- Produces: `StatusResponse.cancelledTrips: Int`, `skippedStopUpdates: Int`, `alerts: Int` — counts from the latest TRIP_UPDATES (and alerts feed) snapshot.

**Security flag:** `none`

**Does NOT cover:** history of counts (analytics records those over time).

- [ ] **Step 1: Test** `status counts cancelled trips, skipped stop updates and alerts` using a feed fixture with 2 CANCELED trips and 3 SKIPPED stop updates; assert 2 / 3.
- [ ] **Step 2: Run** `./gradlew test --tests '*ArrivalsServiceTest*' --offline` — Expected: FAIL.
- [ ] **Step 3: Implement** computing the counts from `store.latest(...)` in `buildStatus` using the existing `isCancelled()` / `isSkipped()` helpers (make them `internal`).
- [ ] **Step 4: Run** same command — Expected: PASS.
- [ ] **Step 5: Commit** "Report cancelled/skipped/alert counts in /v1/status".

### Task 4: `cancelled` on arrivals, opt-in with `includeCancelled=true`

**Files:**
- Modify: `.../core/Models.kt` (`Arrival`), `.../server/ArrivalsService.kt`, `.../server/Dto.kt` (`ArrivalResponse`, `Arrival.toResponse`), `.../server/Routes.kt` (`/stops/{stopId}/arrivals`, `/routes/{route}/arrivals`, `/arrivals`)
- Test: `.../server/ArrivalsServiceTest.kt`, `.../server/RoutesTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Arrival.cancelled: Boolean = false`; `ArrivalResponse.cancelled: Boolean = false`; `ArrivalsService.arrivalsForStop(stopId, routeFilter, limit, includeCancelled: Boolean = false)` and the same parameter on `arrivalsForRoute`; query param `includeCancelled` on the three endpoints; response-cache key for `/routes/{route}/arrivals` includes the flag.

**Security flag:** `none`

**Does NOT cover:** cancelled trips that are no longer in the feed; trips cancelled at stops beyond the lookahead window.

- [ ] **Step 1: Tests**
  - `default arrivals omit a cancelled trip` (existing behaviour, pinned).
  - `includeCancelled returns the cancelled trip with cancelled=true and its scheduled time` .
  - `includeCancelled returns a skipped stop's arrival with cancelled=true`.
  - `route arrivals cache does not mix includeCancelled and default responses`.
  - `ArrivalResponse JSON has cancelled=false by default`.
- [ ] **Step 2: Run** `./gradlew test --tests '*ArrivalsServiceTest*' --tests '*RoutesTest*' --offline` — Expected: FAIL.
- [ ] **Step 3: Implement.** In `arrivalsAtStop`, `suppressedTripIds` currently drops them; when `includeCancelled`, build their `Arrival` from the static timetable (reuse `scheduledArrivalsForStop` with the cancelled ids as the only ones included, then mark `cancelled = true`). Add the flag to the cache key string at `Routes.kt` (`"$identifier|$limit|$includeCancelled"`).
- [ ] **Step 4: Run** the full suite `./gradlew test --offline` — Expected: PASS apart from known timing failures.
- [ ] **Step 5: Commit** "Return cancelled arrivals to clients that opt in".

### Task 5: Go/no-go on NTA cancellation data (manual, before Tasks 6-8)

**Files:** none (observation).

**Interfaces:** Consumes Task 3's status counts. Produces: a recorded decision.

**Security flag:** `none`

- [ ] **Step 1:** Deploy Tasks 1-4 (see Task 9), then poll `/v1/status` for a day and note `cancelledTrips`, `skippedStopUpdates`, `alerts` at several times.
- [ ] **Step 2:** If cancelled and skipped counts are always 0, stop and replan Tasks 6-8 as alerts-only; tell the user.

### Task 6: App shows a cancelled bus as a struck-through row

**Files:** (read first: how `dublin-bus/data` builds the arrivals request and maps `ArrivalResponse`; how `StopDetailViewModel` and the arrival row composable render rows)
- Modify: `dublin-bus/core/src/main/kotlin/dev/dublinbustracker/core/Models.kt` (`Arrival.cancelled: Boolean = false`), the data-layer DTO and request (adds `includeCancelled=true`), the arrival row composable, the widget row if it shares the model.
- Test: the data-layer mapping test and a row UI/preview test following the project's existing pattern.

**Interfaces:**
- Consumes: Task 4's `cancelled` field and `includeCancelled` query param.
- Produces: `Arrival.cancelled` in the app's core model.

**Security flag:** `none`

**Does NOT cover:** notifications or widgets (unless they already share the row composable); cancelled arrivals sorted differently from other rows (they stay in time order).

- [ ] **Step 1: Test** mapping: a response with `"cancelled":true` yields `Arrival.cancelled == true`; a response without the field yields `false`.
- [ ] **Step 2:** Run the module's unit tests — Expected: FAIL.
- [ ] **Step 3: Implement** field, query param and row styling (greyed, strikethrough on the time, label "Cancelled", no countdown). Cancelled rows are excluded from "next bus" summaries and widget headlines.
- [ ] **Step 4:** Run the unit tests and the Appium smoke test if it covers the stop screen (`dublin-bus/appium`, see memory notes on dispatch/cache gotchas) — Expected: PASS.
- [ ] **Step 5: Commit** in the `dublin-bus` repo.

### Task 7: Analytics records cancellations and alerts

**Files:** (read first: `app/core` schema and migrations, `app/collect` `Tracker` and `Poller`, `app/backup` archive/restore treatment of tables, result cache invalidation)
- Modify: the collector to call the server with `includeCancelled=true`; new tables in the schema module; `Tracker` to upsert.
- Create: table `cancellations(trip_id, stop_id, scheduled_epoch, route, kind, first_seen, last_seen, cleared)` unique on `(trip_id, stop_id, scheduled_epoch)`; table `alerts(alert_id, text, valid_from, valid_to, routes, stops, first_seen, last_seen)`.
- Test: collector tests in the existing style.

**Interfaces:**
- Consumes: Task 4's `cancelled` flag; the existing `/v1/alerts` endpoint.
- Produces: tables above; `kind` is `"cancelled"` or `"skipped"`.

**Security flag:** `none`

**Does NOT cover:** backfilling past data; cancelled trips never seen by a poll.

- [ ] **Step 1: Tests** — `a cancelled arrival is stored once across repeated polls`; `a trip that reappears uncancelled sets cleared and keeps one row`; `alerts are upserted by id with first_seen kept`; `existing databases gain the new tables on startup without touching existing rows`.
- [ ] **Step 2:** Run the analytics tests the way the memory notes say (safe running, memory limits; the 3 `test_event_loop_not_blocked` failures are known) — Expected: FAIL.
- [ ] **Step 3: Implement.** Confirm and follow how backup, monthly archive and restore treat existing tables, and give the new tables the same treatment (or document in code why they are exempt).
- [ ] **Step 4:** Run the tests — Expected: PASS.
- [ ] **Step 5: Commit** in the `dublin-bus-analytics` repo.

### Task 8: Analytics API and UI show the cancellation rate

**Files:** (read first: how the delay stats endpoints, rollups and result cache are keyed)
- Modify: the route-stats API module, its web page under `web/js/pages/`, the README API table.
- Test: API tests in the existing style.

**Interfaces:**
- Consumes: Task 7's tables.
- Produces: `cancelled`, `skipped`, `scheduled` counts and `cancellation_rate` in the route stats response.

**Security flag:** `none`

**Does NOT cover:** per-stop heatmaps of cancellations.

- [ ] **Step 1: Tests** — `route stats include cancelled count and rate from the cancellations table`; `rate is null when there were no scheduled trips`; `result cache entries refresh when new cancellations are recorded` (follow how the cache is invalidated for other data).
- [ ] **Step 2–4:** Implement, run tests, verify the page in the built-in browser (not by subagents, memory limits).
- [ ] **Step 5: Commit.**

### Task 9: Deploy to the Pi and measure the baseline (manual)

**Files:** none.

**Security flag:** `none`

- [ ] **Step 1:** Build and deploy the `dublin-bus-server` branch on the Pi (memory: the refresh holds two bundles; watch RAM). The bundle is 13+ days old, so the age check or the detector refreshes it at start.
- [ ] **Step 2:** Confirm stop 414 shows realtime and `GET /v1/status` shows `unmatchedTripUpdates` near 0. Re-run the vehicle-match script. If route ids still don't match (`1_4_c_a` vs `1 4 c a`), stop and fix `scripts/filter_gtfs.py` before anything else.
- [ ] **Step 3:** Record the steady-state `unmatchedTripUpdates` over a day; set `MIN_UNMATCHED` above it if it is not ~0.

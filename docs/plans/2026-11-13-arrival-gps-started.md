# Arrival GPS and "started" indicators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:subagent-driven-development (recommended) or superpowers-optimized:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The stop view shows a GPS icon and a "started its journey" icon on live arrival rows, fed by two new server flags.
**Architecture:** The Ktor server adds `hasGps` and `started` to each arrival from data it already holds (the trip update's reported stop sequence, and the vehicle feed). The Android app parses them (default false) and draws two icons.
**Tech Stack:** Kotlin; Ktor + GTFS-realtime (server, `/home/david/dbus/dublin-bus-server`); Android Compose + kotlinx.serialization (app, `/home/david/dbus/dublin-bus`); JUnit5/kotlin.test.
**Spec:** `/home/david/dbus/dublin-bus-server/docs/specs/2026-11-13-arrival-gps-started-design.md`
**Assumptions:** Assumes the live feed fills `stop_time_update.stop_sequence` (seen as 1..n on the live server) — will NOT give `started` if it does not (flag stays false, no icon). Assumes the vehicles feed is polled about once a minute, so GPS freshness has that granularity.

## Global Constraints
- `hasGps`: vehicle fix under 3 minutes old (180 s). `started`: reported stop's `stop_sequence > 1`. No distance logic anywhere.
- Timetable-only arrivals (`isRealtime = false`): both flags false. No text saying "timetable only" in the new UI.
- New fields default to false on both server DTO and app model, so old server or old app keeps working.
- Server work on a new branch from `main` in `dublin-bus-server`; app work on the existing branch `fix/widget-anr-refresh-spinner` is not reused: branch `feat/arrival-gps-started` from it. Commit per task.
- Content descriptions: "GPS position known", "Started its journey".
- Never call per stop in a loop what can be built once per feed snapshot (route arrivals loops over every stop).

## Review Focus
- Trip update with `stop_sequence` missing/0 on all updates: `started` false, no crash.
- Vehicle fix 10+ minutes old (parked bus): `hasGps` false.
- Route-arrivals path (every stop of a route): vehicle lookup is built once per snapshot, not per stop (existing benchmark test must not regress).
- Old server JSON without the new fields: app parses, flags false.
- Scheduled-only arrival: both flags false.

---

### Task 1: Server — `started` flag

**Files:**
- Modify: `src/main/kotlin/dev/dublinbustracker/server/ArrivalsService.kt` (both live-arrival builders and `firstReport`), `src/main/kotlin/dev/dublinbustracker/server/Dto.kt` (`ArrivalResponse`, `Arrival.toResponse`), the `Arrival` model class used by `ArrivalsService` (grep `data class Arrival(`)
- Test: `src/test/kotlin/dev/dublinbustracker/server/ArrivalsServiceTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `Arrival.started: Boolean = false`, `ArrivalResponse.started: Boolean = false` (JSON field `started`)

**Security flag:** `none`
**Does NOT cover:** buses late to leave their terminus (still marked started once the feed moves past stop 1); distance from the terminus.

- [x] **Step 1: Write failing tests** in `ArrivalsServiceTest.kt`, using that file's existing helpers to build a trip update:
  - `started is false while the report is still at stop sequence 1`: first stop-time update (`stop_sequence = 1`) time is now+60 s → arrival at a later stop has `started == false`.
  - `started is true once the report has moved past stop sequence 1`: update for seq 1 has time now-300 s, seq 2 has now+60 s → `started == true`.
  - `started stays false when the feed gives no stop sequence`: all updates `stop_sequence` unset → `started == false`.
  - `scheduled-only arrivals are not started`: arrival with `isRealtime = false` → `started == false`.
- [x] **Step 2: Run** `./gradlew test --tests '*ArrivalsServiceTest*'` → FAIL (no `started`).
- [x] **Step 3: Implement.** Make `firstReport(now)` also return the chosen `StopTimeUpdate`'s `stopSequence` (a small result type, not a Triple); set `started = reportedSequence > 1` on live arrivals in both builders (the one at ~line 187 and ~line 297); add the field to `Arrival`, `ArrivalResponse` and `toResponse()`.
- [x] **Step 4: Run** the same command → PASS; then `./gradlew test` → all PASS.
- [x] **Step 5: Commit** on branch `feat/arrival-gps-started`: `git add -A && git commit -m "Report whether a live arrival's trip has started its journey"` (end with the attribution line from the session).

### Task 2: Server — `hasGps` flag

**Files:**
- Modify: `src/main/kotlin/dev/dublinbustracker/server/VehicleService.kt` (or `ArrivalsService.kt` if it already holds the feed store), `ArrivalsService.kt`, `Dto.kt`, the `Arrival` model
- Test: `ArrivalsServiceTest.kt` (and `VehicleServiceTest.kt` for the lookup)

**Interfaces:**
- Consumes: `Arrival`/`ArrivalResponse` from Task 1 (same files)
- Produces: `Arrival.hasGps: Boolean = false`, `ArrivalResponse.hasGps: Boolean = false`; a once-per-VEHICLES-snapshot set of trip ids with a fix newer than 180 s (e.g. `VehicleService.tripsWithFreshFix(now: Long): Set<String>`, cached by snapshot identity)

**Security flag:** `none`
**Does NOT cover:** whether the position is near the stop or terminus; a fix older than 180 s counts as no GPS.

- [x] **Step 1: Write failing tests:**
  - `hasGps is true when the vehicle feed has a fresh fix for the trip` (fix timestamp now-30 s).
  - `hasGps is false for a fix older than three minutes` (now-600 s).
  - `hasGps is false when no vehicle reports the trip`.
  - `scheduled-only arrivals never have gps`.
  - `the fresh-trip set is built once per snapshot`: call twice on the same snapshot, assert the underlying feed entities are scanned once (use the test's fake/counting store as `ResponseCache` tests do).
- [x] **Step 2: Run** `./gradlew test --tests '*VehicleServiceTest*' --tests '*ArrivalsServiceTest*'` → FAIL.
- [x] **Step 3: Implement** the cached set in `VehicleService` and set `hasGps = tripId in fresh` on live arrivals only; add to DTO and `toResponse()`.
- [x] **Step 4: Run** the same command then `./gradlew test` (includes `ArrivalsBenchmarkTest`) → PASS, benchmark not slower than the committed "after" figure by more than noise.
- [x] **Step 5: Commit**: `git commit -m "Report whether a live arrival's bus has a fresh GPS fix"`.

### Task 3: App — model and parsing

**Files:**
- Modify: `core/src/main/kotlin/dev/dublinbustracker/core/Models.kt` (`Arrival`), the arrival DTO and its mapping in `data/src/main/kotlin/dev/dublinbustracker/data/TransitApiClient.kt`
- Test: `data/src/test/kotlin/dev/dublinbustracker/data/TransitApiClientTest.kt`

**Interfaces:**
- Consumes: server JSON fields `hasGps`, `started` (Tasks 1-2)
- Produces: `Arrival.hasGps: Boolean = false`, `Arrival.started: Boolean = false`

**Security flag:** `none`
**Does NOT cover:** widget or map use of the flags.

- [x] **Step 1: Write failing tests** (MockWebServer, as the existing arrival tests do): `arrivals carry the gps and started flags from the server` (JSON has `"hasGps":true,"started":true` → both true) and `arrivals from an older server without the flags parse as false`.
- [x] **Step 2: Run** `./gradlew :data:test --tests '*TransitApiClientTest*'` → FAIL.
- [x] **Step 3: Implement** the two fields with default `false` in `Arrival` and the DTO, mapped across.
- [x] **Step 4: Run** `./gradlew test` → PASS.
- [x] **Step 5: Commit** on branch `feat/arrival-gps-started` (app repo): `git commit -m "Read the arrival GPS and started flags"`.

### Task 4: App — display and icons in the stop view

**Files:**
- Modify: `core/src/main/kotlin/dev/dublinbustracker/core/ArrivalDisplay.kt` (`ArrivalDisplay`, `Arrival.display`), `app/src/main/kotlin/dev/dublinbustracker/app/detail/StopDetailScreen.kt` (arrival row composable)
- Test: `core/src/test/kotlin/dev/dublinbustracker/core/ArrivalDisplayTest.kt` (create if absent, matching neighbours' style)

**Interfaces:**
- Consumes: `Arrival.hasGps`, `Arrival.started` (Task 3)
- Produces: `ArrivalDisplay.hasGps: Boolean`, `ArrivalDisplay.started: Boolean`

**Security flag:** `none`
**Does NOT cover:** icons on the widget, map, or bus info sheet.

- [x] **Step 1: Write failing tests:** `display carries hasGps and started` (live arrival with both true → both true on the `ArrivalDisplay`); `a timetable-only arrival shows neither` (isRealtime false, flags false → both false and `detailLine` unchanged from today).
- [x] **Step 2: Run** `./gradlew :core:test --tests '*ArrivalDisplayTest*'` → FAIL.
- [x] **Step 3: Implement** the two fields on `ArrivalDisplay`, copied in `Arrival.display`; in the row composable draw a small GPS icon (`Icons.Filled.GpsFixed`, if not in the icon set used, use `LocationOn`) when `hasGps` and a small forward-arrow icon (`Icons.Filled.Navigation`) when `started`, 16 dp, with the content descriptions from Global Constraints, placed beside the headline; muted rows keep their muted tint.
- [x] **Step 4: Run** `./gradlew test :app:assembleDebug` → PASS and APK builds.
- [x] **Step 5: Commit**: `git commit -m "Show GPS and started icons on stop view arrivals"`.

---
**Self-review done:** every spec section maps to a task (flags → 1-2, parsing → 3, UI → 4, non-goals untouched). Names consistent across Interfaces blocks. No distance logic or "timetable only" text anywhere. Deploy of the server to the Pi is the user's step.

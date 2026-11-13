# Route Arrivals Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:subagent-driven-development (recommended) or superpowers-optimized:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GET /v1/routes/{route}/arrivals` fast enough on a Raspberry Pi that analytics stops timing out, and make analytics tolerant of slow responses.
**Architecture:** Server: a new `ArrivalsService.arrivalsForRoute` filters the feed to the route's trips once and reuses the existing per-stop logic through one shared private core; a small generic `ResponseCache` (per feed snapshot, 15 s TTL, single-flight) fronts the route handler. Analytics: configurable read timeout and a semaphore limiting concurrent route fetches.
**Tech Stack:** Kotlin/Ktor/Gradle (`dublin-bus-server`), Python 3.12/FastAPI/httpx/pytest (`dublin-bus-analytics`).
**Spec:** `/home/david/dbus/dublin-bus-server/docs/specs/2026-11-11-route-arrivals-efficiency-design.md` (plan conflicts resolve against the spec).
**Assumptions:**
- Assumes a trip belongs to a route iff `staticData.current.trip(id)?.routeId ?: update.trip.routeId` is in `resolveRoutes(identifier)` ids. Will NOT hold if another stage of `arrivalsForStop` stops filtering by that same rule; the differential test (Task 2) is the guard.
- Assumes the slowness is the per-stop feed rescan. If Task 5's measurement says otherwise, stop and report; do not guess a further fix.

## Global Constraints
- Models: use **sonnet or cheaper only** for every subagent, never opus. At most **2 agents at once**.
- RAM: WSL has about 7.7 GB and crashes when exhausted. Gradle: `./gradlew --no-daemon ...`, then `./gradlew --stop`; never run two Gradle builds at once. Python: prefix with `ulimit -v 2500000;` and use `/home/david/dbus/.venv/bin/python -m pytest ... --no-cov`; never run the whole analytics suite, only the named files.
- No JSON response shape change; `/v1/stops/*/arrivals` behaviour unchanged; `arrivalsForStop` stays the reference implementation.
- Cache: key = identifier as given + limit; valid while TRIP_UPDATES and COMBINED snapshot `fetchedAtEpochMs` are unchanged, `staticData.current` is the same instance (`===`), and age <= 15 s; max 256 entries, cleared on a new snapshot; concurrent requests for a key share one computation.
- Analytics: `SERVER_TIMEOUT_SECONDS` default 45 (read timeout; connect stays 5 s), `SERVER_CONCURRENCY` default 2. Gap semantics unchanged.
- Do not touch the uncommitted `Dockerfile` / `docker/entrypoint.sh` edits in `/home/david/dbus/dublin-bus-server` (main checkout). Server work happens in a separate worktree.
- Do not build or push images.

## Review Focus
- Trip with no static entry and a feed route id: direct arrival must still show; cascaded must not (matches `arrivalsForStop`). Pinned in Task 2.
- Cancelled trip and trip skipping one stop: absent at that stop only for skip, absent everywhere for cancel. Pinned in Task 2.
- Loop trip calling a stop twice and scheduled-only trips (no live update) on the route. Pinned in Task 2.
- Cache must not serve a response across a static-data swap or a new feed snapshot, and must not grow past 256 entries. Pinned in Task 3.
- A fetch that times out client-side must not leave the single-flight lock held (computation failure propagates, next caller retries). Pinned in Task 3.
- Known, unchanged: sample timestamps use tick start, not fetch completion (out of scope).

---

### Task 1: Worktree and baseline timing

**Files:**
- Create: `/home/david/dbus/dublin-bus-server-perf/src/test/kotlin/dev/dublinbustracker/server/ArrivalsBenchmarkTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `ArrivalsBenchmarkTest` with a helper `syntheticFeed(): Pair<StaticDataRef, FeedStore>` (about 3000 trip updates across about 40 routes, one route "BENCH" with 80 stops and about 60 live trips) that Tasks 2 and 5 reuse.

**Security flag:** `none`
**Does NOT cover:** correctness (Task 2); the benchmark asserts nothing and only runs when env `BENCH=1`.

- [ ] **Step 1: Create the worktree**

Run: `cd /home/david/dbus/dublin-bus-server && git worktree add ../dublin-bus-server-perf -b perf/route-arrivals feat/scheduled-epoch`
Expected: worktree at `/home/david/dbus/dublin-bus-server-perf` on branch `perf/route-arrivals`; `git status` in the main checkout still shows the two modified files.

- [ ] **Step 2: Write the benchmark** `ArrivalsBenchmarkTest.benchmarkPerStopLoop`

Skips unless `System.getenv("BENCH") == "1"`. Build the synthetic static bundle by following `Fixtures.staticBundleJson()` in `src/test/kotlin/dev/dublinbustracker/support/Fixtures.kt` (same JSON shape, scaled up) and the feed with `Fixtures.feedOf`/`tripUpdateEntity`. Times the old handler behaviour: for each of the route's stop ids call `arrivalsForStop(stopId, "BENCH", 50)`, repeated 3 times; `println("BENCH per-stop loop ms: ...")` best and worst.

- [ ] **Step 3: Run and record**

Run: `cd /home/david/dbus/dublin-bus-server-perf && BENCH=1 ./gradlew --no-daemon test --tests '*ArrivalsBenchmarkTest*' -i | grep BENCH; ./gradlew --stop`
Expected: a `BENCH per-stop loop ms:` line. Write the numbers into the commit message.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/dev/dublinbustracker/server/ArrivalsBenchmarkTest.kt
git commit -m "Add gated arrivals benchmark; baseline per-stop loop: <numbers>"
```

---

### Task 2: `arrivalsForRoute` with differential test

**Files:**
- Modify: `/home/david/dbus/dublin-bus-server-perf/src/main/kotlin/dev/dublinbustracker/server/ArrivalsService.kt`
- Modify (only if needed for the cases below): `src/test/kotlin/dev/dublinbustracker/support/Fixtures.kt`
- Test: `src/test/kotlin/dev/dublinbustracker/server/ArrivalsServiceTest.kt`

**Interfaces:**
- Consumes: `ArrivalsService(store, staticData, nowEpochSeconds)`, existing `arrivalsForStop(stopId: String, routeFilter: String? = null, limit: Int = 50): List<Arrival>`.
- Produces: `fun arrivalsForRoute(routeIdentifier: String, stopIds: Collection<String>, limit: Int = 50): Map<String, List<Arrival>>` on `ArrivalsService`. Stops with no arrivals are omitted from the map; map order follows `stopIds` order.

**Security flag:** `none`
**Does NOT cover:** caching (Task 3); `/v1/stops/*/arrivals` is untouched.

- [ ] **Step 1: Write failing test** `arrivalsForRoute equals per-stop arrivalsForStop`

In `ArrivalsServiceTest`: for a feed built with `Fixtures` containing (a) live trips on the route with delays, (b) a cancelled trip, (c) a trip with one SKIPPED stop, (d) a loop trip calling one stop twice, (e) a trip with no static entry but a feed `routeId` of the route, (f) a scheduled-only trip with no live update, (g) trips on other routes: for every stop id of the route (from `staticData.current.patternsForRoute(route.id).values.flatten().distinct()`) and for limits 1 and 50, assert `service.arrivalsForRoute(id, stops, limit) == stops.mapNotNull { s -> service.arrivalsForStop(s, id, limit).takeIf { it.isNotEmpty() }?.let { s to it } }.toMap()`. If `Fixtures` lacks a way to mark a trip CANCELED or a stop SKIPPED, add optional parameters (default off) to `tripUpdateEntity` / `stopTimeUpdate`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --no-daemon test --tests '*ArrivalsServiceTest*'; ./gradlew --stop`
Expected: FAIL (compilation error: unresolved `arrivalsForRoute`).

- [ ] **Step 3: Implement** `arrivalsForRoute` in `ArrivalsService.kt`

Extract the body of `arrivalsForStop` into a private function taking `(stopId, now, wantedRouteIds, updates: List<TripUpdate>, limit)`; `arrivalsForStop` computes `now`, `wantedRouteIds` and all updates, then calls it. `arrivalsForRoute` resolves `wantedRouteIds` with `staticData.current.resolveRoutes(routeIdentifier)`, computes `now` and the full update list once, keeps only updates whose `staticData.current.trip(id)?.routeId ?: update.trip.routeId` is wanted, and calls the private function per stop. Return empty map when no route resolves.

- [ ] **Step 4: Run test to verify it passes**

Run: same command. Expected: PASS, including every existing `ArrivalsServiceTest` test.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "Add ArrivalsService.arrivalsForRoute sharing the per-stop core"
```

---

### Task 3: `ResponseCache`

**Files:**
- Create: `/home/david/dbus/dublin-bus-server-perf/src/main/kotlin/dev/dublinbustracker/server/ResponseCache.kt`
- Test: `/home/david/dbus/dublin-bus-server-perf/src/test/kotlin/dev/dublinbustracker/server/ResponseCacheTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `class ResponseCache<V : Any>(ttlMs: Long = 15_000, maxEntries: Int = 256, nowMs: () -> Long = System::currentTimeMillis)` with `suspend fun getOrCompute(key: String, feedStamp: List<Long?>, staticData: Any, compute: () -> V): V`. Entries are shared, so `V` must be treated as immutable by callers.

**Security flag:** `none`
**Does NOT cover:** deciding what the stamp is (Task 4); caching failures (a throwing `compute` is not cached).

- [ ] **Step 1: Write failing tests** (kotlinx-coroutines-test is available; inject `nowMs`)
- `second call with same stamp within ttl does not recompute`
- `changed feedStamp recomputes and drops older entries`
- `ttl expiry recomputes` (advance `nowMs` by 15_001)
- `different staticData instance recomputes` (two `Any()` objects)
- `concurrent callers for one key compute once` (10 coroutines, `compute` counts calls and suspends via a blocking-safe latch; assert count == 1 and all results equal)
- `compute exception propagates, is not cached, and next call recomputes`
- `entry count never exceeds maxEntries` (maxEntries = 3, 10 distinct keys)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew --no-daemon test --tests '*ResponseCacheTest*'; ./gradlew --stop`
Expected: FAIL (unresolved `ResponseCache`).

- [ ] **Step 3: Implement `ResponseCache`** in `ResponseCache.kt`

One `Mutex` guards the map (an entry holds value, stamp, staticData ref compared with `===`, and computed-at). Single-flight: computing happens while holding a per-key `Mutex` so waiters re-check the entry after acquiring it. Evict the oldest entry when over `maxEntries`; when `feedStamp` differs from the stored stamp for any entry, drop that entry.

- [ ] **Step 4: Run to verify pass**

Run: same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "Add ResponseCache: per-snapshot TTL cache with single-flight"
```

---

### Task 4: Wire the route handler

**Files:**
- Modify: `src/main/kotlin/dev/dublinbustracker/server/ArrivalsService.kt`, `src/main/kotlin/dev/dublinbustracker/server/AppServices.kt`, `src/main/kotlin/dev/dublinbustracker/server/Routes.kt`
- Test: `src/test/kotlin/dev/dublinbustracker/server/RoutesTest.kt`

**Interfaces:**
- Consumes: `ArrivalsService.arrivalsForRoute(routeIdentifier, stopIds, limit)` (Task 2), `ResponseCache.getOrCompute(key, feedStamp, staticData, compute)` (Task 3), `ArrivalResponse` via `Arrival.toResponse()`.
- Produces: `fun feedStamp(): List<Long?>` on `ArrivalsService` (fetchedAtEpochMs of the latest TRIP_UPDATES then COMBINED snapshots, null if absent); `val routeArrivalsCache: ResponseCache<Map<String, List<ArrivalResponse>>>` on `AppServices` (last constructor parameter with a default so existing constructions compile).

**Security flag:** `none`
**Does NOT cover:** `/v1/stops/*/arrivals` and `/v1/arrivals` stay uncached.

- [ ] **Step 1: Write failing tests** in `RoutesTest`
- `route arrivals identical on repeat requests within a snapshot` (two GETs, equal bodies).
- `route arrivals recomputed after a new snapshot is recorded` (record a changed feed via the store, second GET reflects it).
- Existing route-arrivals tests (limit, 404 unknown route) still pass unchanged.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew --no-daemon test --tests '*RoutesTest*'; ./gradlew --stop`
Expected: the new snapshot test FAILs only if wiring is absent; if both already pass, add an assertion that the handler goes through the cache (inject a counting `compute` by exposing a hit counter on `ResponseCache` for tests) so the test fails first.

- [ ] **Step 3: Implement**

In `Routes.kt` replace the per-stop loop at the `/routes/{route}/arrivals` handler: resolve the route as today (404 unchanged), then `services.routeArrivalsCache.getOrCompute("$identifier|$limit", services.arrivals.feedStamp(), data) { services.arrivals.arrivalsForRoute(identifier, stopIds, limit).mapValues { (_, v) -> v.map { it.toResponse() } } }` and respond with the map. `stopIds` stays `data.patternsForRoute(route.id).values.flatten().distinct()`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew --no-daemon test; ./gradlew --stop`
Expected: whole server suite PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "Serve route arrivals through arrivalsForRoute and a per-snapshot cache"
```

---

### Task 5: Re-measure and gate

**Files:**
- Modify: `src/test/kotlin/dev/dublinbustracker/server/ArrivalsBenchmarkTest.kt`

**Interfaces:**
- Consumes: `syntheticFeed()` (Task 1), `arrivalsForRoute` (Task 2).
- Produces: nothing

**Security flag:** `none`
**Does NOT cover:** any further optimisation; this task only measures and reports.

- [ ] **Step 1: Add `benchmarkArrivalsForRoute`** timing 3 cold calls of `arrivalsForRoute("BENCH", stops, 50)` (best and worst, `println("BENCH route ms: ...")`).
- [ ] **Step 2: Run**: `BENCH=1 ./gradlew --no-daemon test --tests '*ArrivalsBenchmarkTest*' -i | grep BENCH; ./gradlew --stop`. Expected: `BENCH route ms` lines.
- [ ] **Step 3: Gate.** Compare with Task 1. If the route time is at least 10x lower than the per-stop loop, continue. If not, or if the route time is still above 1000 ms on this machine, **stop and report both numbers to the user**; do not implement a per-route scheduled scan without approval.
- [ ] **Step 4: Commit** with both numbers in the message: `git add src/test && git commit -m "Benchmark arrivalsForRoute: before <x> ms, after <y> ms"`.

---

### Task 6: Analytics settings

**Files** (repo `/home/david/dbus/dublin-bus-analytics`; first run `git checkout -b perf/timeout-concurrency main`, the spec file there is untracked and stays untracked):
- Modify: `app/core/config.py`, `analytics.env.example`, `README.md`, `tests/test_docs.py`
- Test: `tests/core/test_config.py` (create it if absent, else add to the existing config test file found with `grep -rl "Settings.from_env" tests`)

**Interfaces:**
- Consumes: `Settings` dataclass and `_pos_int`.
- Produces: `Settings.server_timeout_seconds: int = 45` and `Settings.server_concurrency: int = 2` (appended after the existing fields so positional construction `Settings("http://fake", tmp_path, 30)` keeps working), read from `SERVER_TIMEOUT_SECONDS` and `SERVER_CONCURRENCY` via `_pos_int`.

**Security flag:** `none`
**Does NOT cover:** compose files (the sample compose does not pass these; users add them under `environment:` if they want to change defaults).

- [ ] **Step 1: Write failing tests**: defaults are 45 and 2; env values parse; `SERVER_CONCURRENCY=0` and `SERVER_TIMEOUT_SECONDS=abc` raise `ValueError`; in `tests/test_docs.py` extend `test_app_vars_detected` to include `SERVER_TIMEOUT_SECONDS` and `SERVER_CONCURRENCY` and the parse assertions in `test_env_example_app_level_names_parse_into_settings`.
- [ ] **Step 2: Run**: `ulimit -v 2500000; /home/david/dbus/.venv/bin/python -m pytest tests/test_docs.py tests/core -q --no-cov -p no:cacheprovider`. Expected: FAIL.
- [ ] **Step 3: Implement** the two fields in `config.py`; add commented lines `# SERVER_TIMEOUT_SECONDS=45` and `# SERVER_CONCURRENCY=2` with one-line descriptions to the app-level section of `analytics.env.example`, and two rows to the README config table (next to `POLL_SECONDS`): read timeout for server calls in seconds (positive integer); routes fetched at once (positive integer, lower is gentler on a Raspberry Pi).
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit**: `git add app/core/config.py analytics.env.example README.md tests && git commit -m "Add SERVER_TIMEOUT_SECONDS and SERVER_CONCURRENCY settings"`.

---

### Task 7: Configurable read timeout in `ServerClient`

**Files:**
- Modify: `app/collect/serverclient.py`
- Test: `tests/collect/test_serverclient.py`

**Interfaces:**
- Consumes: `Settings.server_timeout_seconds` (Task 6).
- Produces: `ServerClient(base_url: str, client: httpx.AsyncClient | None = None, read_timeout_s: float = 45.0)`; the per-call `timeout=` and the default client both use `httpx.Timeout(read_timeout_s, connect=5.0)`. Module constant `TIMEOUT` is removed.

**Security flag:** `none`
**Does NOT cover:** retries; a timeout still raises `ServerUnavailable`.

- [ ] **Step 1: Write failing test** `test_read_timeout_is_configurable`: build `ServerClient("http://x", client=<stub recording the timeout kwarg>, read_timeout_s=7)`, call `status()`, assert the recorded timeout has `read == 7.0` and `connect == 5.0`; and that the default is `45.0`.
- [ ] **Step 2: Run**: `ulimit -v 2500000; /home/david/dbus/.venv/bin/python -m pytest tests/collect/test_serverclient.py -q --no-cov -p no:cacheprovider`. Expected: FAIL.
- [ ] **Step 3: Implement** the constructor parameter and replace both `TIMEOUT` uses; check `grep -rn "serverclient.TIMEOUT\|import TIMEOUT" app tests` shows no other user.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit**: `git add app/collect/serverclient.py tests/collect/test_serverclient.py && git commit -m "Make the server read timeout configurable"`.

---

### Task 8: Limit concurrent route fetches in the poller

**Files:**
- Modify: `app/collect/poller.py`
- Test: `tests/collect/test_poller.py`

**Interfaces:**
- Consumes: `Settings.server_concurrency` (Task 6), `Poller._one(route, now)`, `Poller.tick(now)`.
- Produces: `Poller.tick` fetches at most `settings.server_concurrency` routes at once. Only the `client.route_arrivals` call is inside the limit; ingest and gap logic unchanged.

**Security flag:** `none`
**Does NOT cover:** reducing `POLL_SECONDS` drift; a tick that overruns is still realigned by `run` and missed slots are dropped.

- [ ] **Step 1: Write failing tests** using the existing `Env`/`FakeServer` pattern in `tests/collect/test_poller.py`:
  - `test_tick_never_exceeds_server_concurrency`: 6 tracked routes, `Settings(..., server_concurrency=2)` (keyword), a fake client whose `route_arrivals` records current/peak in-flight count and awaits `asyncio.sleep(0)` twice; after `tick(T0)` assert peak == 2 and all 6 routes were fetched.
  - `test_failure_in_one_route_does_not_block_others`: concurrency 1, first route raises `ServerUnavailable`, assert remaining routes are still fetched and no gap row is written (fetched > 0).
- [ ] **Step 2: Run**: `ulimit -v 2500000; /home/david/dbus/.venv/bin/python -m pytest tests/collect/test_poller.py -q --no-cov -p no:cacheprovider -k "concurrency or block_others"`. Expected: FAIL (peak == 6).
- [ ] **Step 3: Implement** in `poller.py`: create `asyncio.Semaphore(self.settings.server_concurrency)` inside `tick` and pass it to `_one` (new optional parameter `sem: asyncio.Semaphore | None = None`; `None` means unlimited so existing direct `_one` callers keep working); wrap only the `route_arrivals` await with `async with sem`.
- [ ] **Step 4: Run** the full file: `/home/david/dbus/.venv/bin/python -m pytest tests/collect/test_poller.py -q --no-cov -p no:cacheprovider`. Expected: PASS.
- [ ] **Step 5: Commit**: `git add app/collect/poller.py tests/collect/test_poller.py && git commit -m "Limit concurrent route fetches per poll tick"`.

---

### Task 9: Wire settings into the app

**Files:**
- Modify: `app/main.py`
- Test: `tests/test_main.py`

**Interfaces:**
- Consumes: `ServerClient(base_url, client, read_timeout_s)` (Task 7), `Settings.server_timeout_seconds`.
- Produces: `create_app` builds `ServerClient(settings.server_url, read_timeout_s=settings.server_timeout_seconds)` when no client is injected.

**Security flag:** `none`
**Does NOT cover:** injected test clients (used as given).

- [ ] **Step 1: Write failing test** `test_create_app_uses_configured_timeout`: `create_app(Settings("http://fake", tmp_path, 30, server_timeout_seconds=9), start_background=False)`; assert the app's client read timeout is 9 (via the client's `_client.timeout.read`).
- [ ] **Step 2: Run**: `ulimit -v 2500000; /home/david/dbus/.venv/bin/python -m pytest tests/test_main.py -q --no-cov -p no:cacheprovider`. Expected: FAIL.
- [ ] **Step 3: Implement** the one-line change in `create_app`.
- [ ] **Step 4: Run** the same command plus `tests/collect/test_serverclient.py tests/collect/test_poller.py tests/test_docs.py`. Expected: PASS.
- [ ] **Step 5: Commit**: `git add app/main.py tests/test_main.py && git commit -m "Wire SERVER_TIMEOUT_SECONDS into the server client"`.

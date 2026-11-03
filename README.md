# Dublin Bus Server

Caching API server for Irish public transport realtime data, built for the
[Dublin Bus Tracker](../dublin-bus) app but usable by anything.

It polls the NTA GTFS-Realtime feeds on its own rate-limited schedule, keeps the results in
memory, and answers questions about them. Clients get an ordinary JSON API with no rate limit
of their own, because one upstream poll serves every client and every stop.

Covers Dublin Bus, Bus Éireann and Go-Ahead Ireland.

## Quick start (Docker)

```bash
cp server.env.example server.env    # fill in GTFSR_API_KEY
docker compose up -d
```

On first start the container downloads the static GTFS feed and filters it into a bundle on
the `/data` volume. That takes a few minutes and only happens once; afterwards startup is
seconds. Then:

```bash
curl localhost:8080/v1/status
```

There is no server-side auth; this is meant to sit behind something that already gates access
(e.g. a Cloudflare Access / Tunnel in front of it), not to be exposed directly.

Set `REFRESH_GTFS_ON_START=true` to rebuild the bundle (timetables change periodically).

### Running without Docker

```bash
./scripts/fetch-gtfs-data.sh    # writes src/main/resources/gtfs_static.json
./scripts/run-server.sh         # or --build for a standalone installDist
./scripts/test.sh               # unit tests, no network needed
./scripts/live-smoke-test.sh    # runs the real server against the live NTA API and checks the data
```

### Behind a TLS-inspecting proxy

Drop the proxy's root CAs into `certs/` before building; they are installed into both the OS
and JVM trust stores. See `certs/README.md`. Leave it empty on a normal network.

## Configuration

All configuration is environment variables (see `server.env.example`).

| Variable | Default | Purpose |
| --- | --- | --- |
| `GTFSR_API_KEY` | *required* | NTA key ([sign up](https://developer.nationaltransport.ie/signup)) |
| `GTFSR_API_KEY_SECONDARY` | – | Optional second key, doubling the upstream budget |
| `PORT` | `8080` | Listen port |
| `FEEDS` | `TRIP_UPDATES,VEHICLES` | Which feeds to poll |
| `POLL_INTERVAL_MS` | fastest safe value | Length of one poll cycle |
| `HISTORY_DEPTH` | `30` | Snapshots retained per feed |
| `ALLOW_UNSAFE_POLL_RATE` | `false` | Permit exceeding the fair-usage limit |
| `GTFS_STATIC_PATH` | bundled resource | Where the static bundle lives |
| `GTFS_REFRESH_INTERVAL_HOURS` | `24` | How often the static bundle re-fetches itself (see below) |
| `GTFSR_BASE_URL` | NTA v2 endpoint | Override for testing |
| `NTFY_URL` | – | Push URL for a self-hosted [ntfy](https://ntfy.sh) topic. Blank disables alerting |
| `NTFY_LEVEL` | `info` | How much to push: `off`, `error`, `warn`, `info` (see below) |

### What it fetches

`FEEDS` selects which GTFS-Realtime feeds are polled:

- `TRIP_UPDATES` — predicted arrival times. Powers arrivals and trip progress.
- `VEHICLES` — live positions. Powers vehicle endpoints and history tracks.
- `COMBINED` — the NTA's combined feed, and **the only source of service alerts**. Add it if
  you want `/v1/alerts` to return anything.

### How often, and the rate limit

The NTA fair-usage policy allows **one call per 60 seconds per key**, and the limit is per
key rather than per feed. One poll cycle fetches every enabled feed once, so:

```
upstream calls per minute = feeds x 60000 / POLL_INTERVAL_MS
budget                    = number of keys per minute
```

The default `POLL_INTERVAL_MS` is the fastest value that stays inside the budget, i.e.
`60000 x feeds / keys`. Worked examples:

| Feeds | Keys | Default interval | Effect |
| --- | --- | --- | --- |
| 1 | 1 | 60s | Arrivals refresh every minute |
| 2 | 1 | 120s | Arrivals and positions every two minutes |
| 2 | 2 | 60s | Both feeds every minute |
| 1 | 2 | 30s | Arrivals every 30s, the fastest useful setup |

Setting `POLL_INTERVAL_MS` below the safe minimum aborts startup, because the practical result
is HTTP 429s. Override with `ALLOW_UNSAFE_POLL_RATE=true` only if your keys have a raised
limit. `/v1/status` reports the configured rate, the safe minimum, and the running call count.

A poll that fails at the network level (timeout, connection reset) is retried up to twice more
with backoff, since those are usually transient. A poll that fails with an HTTP error status
(e.g. a 429) is not retried — retrying it immediately would just repeat it and burn more of the
per-key budget.

### Alerting

Set `NTFY_URL` to a self-hosted [ntfy](https://ntfy.sh) topic URL to get a push notification
when things go wrong. `NTFY_LEVEL` controls how much, like a log level — each level includes
everything more severe than it:

| Level | Sends |
| --- | --- |
| `off` | Nothing |
| `error` | The server failed to start |
| `warn` | + a feed has failed 3 cycles in a row (likely stale) or just recovered |
| `info` (default) | + every single poll failure, including transient ones already retried away |

### Backlog

`HISTORY_DEPTH` is how many past snapshots of each feed are kept in memory. This is what makes
historical queries possible without a database — `/v1/vehicles/{id}/history` replays the
retained snapshots into a track. Reachable history is roughly
`HISTORY_DEPTH x POLL_INTERVAL_MS`, so the default 30 snapshots on a 60s cycle covers the last
30 minutes. Raising it costs memory roughly linearly (a Vehicles snapshot is a few MB).

## API

There is no auth on this server — see the note under Quick start. Routes accept either a GTFS
route id or a bus number (`15`, `40D`), and stops accept either a GTFS stop id or the number
printed at the roadside. `limit` defaults to 50 and caps at 500.

| Endpoint | Returns |
| --- | --- |
| `GET /health` | Liveness |
| `GET /v1/status` | Poll rate, backlog depth, feed freshness, bundle stats |
| `GET /v1/stops?query=&limit=` | Stop search by name or number |
| `GET /v1/stops/nearby?lat=&lon=&radius=&limit=` | Stops near a point (radius in metres, default 500) |
| `GET /v1/stops/{stop}` | One stop, with the routes serving it |
| `GET /v1/stops/{stop}/arrivals?route=&limit=` | Live arrivals, optionally for one bus number |
| `GET /v1/routes?query=&limit=` | Route search |
| `GET /v1/routes/{route}` | Route variants sharing a bus number |
| `GET /v1/routes/{route}/stops?direction=` | Ordered stops along the route, per direction |
| `GET /v1/routes/{route}/shape?direction=&format=points` | Route geometry as an encoded polyline |
| `GET /v1/routes/{route}/vehicles` | Every bus currently running that route |
| `GET /v1/vehicles?route=&bbox=&limit=` | Live positions, filterable by route or bounding box |
| `GET /v1/vehicles/{id}` | One bus, with its route and trip |
| `GET /v1/vehicles/{id}/history` | That bus's recent track from the backlog |
| `GET /v1/trips/{id}` | Trip detail: route, headsign, live vehicle, remaining stop calls |
| `GET /v1/trips/{id}/shape` | That trip's geometry |
| `GET /v1/alerts?route=&stop=` | Service disruptions (needs `COMBINED` in `FEEDS`) |

`bbox` is `minLat,minLon,maxLat,maxLon`. Shapes return a
[Google encoded polyline](https://developers.google.com/maps/documentation/utilities/polylinealgorithm);
add `format=points` to also get expanded `[lat, lon]` pairs.

`GET /arrivals?stopId=` and `GET /stops?query=` are kept unversioned and unchanged for the
Android app that already ships against them.

### Example

```bash
curl "localhost:8080/v1/stops?query=Trinity"     # find a stop
curl "localhost:8080/v1/stops/1001/arrivals"     # what's due there
curl "localhost:8080/v1/routes/15/stops"         # where route 15 goes
curl "localhost:8080/v1/routes/15/vehicles"      # where its buses are now
curl "localhost:8080/v1/vehicles/226/history"    # where one has been
```

## Static GTFS bundle

`scripts/fetch-gtfs-data.sh` downloads the ~700MB static GTFS zip and filters it to the three
agencies, producing a ~36MB JSON bundle. `shapes.txt` and `stop_times.txt` dominate that zip,
so the script deduplicates and polyline-encodes shapes, and reduces stop times to one
representative stop pattern per route and direction rather than retaining all 248k trips'
timetables. Use `--no-shapes` or `--no-patterns` for a smaller bundle.

NTA revises the static schedule periodically, and when it does, the GTFS-Realtime feed's trip
ids stop matching the bundle - trip headsigns go blank and `/v1/trips/{id}` 404s, even though
stops, routes and vehicle positions are unaffected (they resolve by route/stop id, not trip id).
The server re-runs this same pipeline on its own every `GTFS_REFRESH_INTERVAL_HOURS` (default
24) and hot-swaps the result in, so this self-heals without a restart. A failed refresh just
logs (and pushes a `warn`-level alert if `NTFY_URL` is set) and keeps serving the old bundle.

## Notes

- The design assumes one upstream poll serves everyone: adding clients, stops or routes costs
  nothing upstream. Only adding *feeds* or polling *faster* does.
- The bundle is regenerated, not migrated. After changing the filter script, re-run it.
- No database. State is the in-memory backlog, so a restart loses history but nothing else.

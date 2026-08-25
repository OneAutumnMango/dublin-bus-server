# Dublin Bus Server

Standalone Ktor service for the [Dublin Bus Tracker](../dublin-bus) app. Polls the NTA
GTFS-Realtime feed on its own rate-limited schedule and caches it, so the app (and any number
of other clients) can ask for arrivals without caring about NTA's per-key rate limit.

## Setup
```
./scripts/fetch-gtfs-data.sh     # downloads + filters static GTFS into src/main/resources/gtfs_static.json
cp server.env.example server.env # fill in SERVER_API_KEY, GTFSR_API_KEY (+ optional secondary)
./scripts/run-server.sh          # run via Gradle, or --build for a standalone installDist binary
./scripts/test.sh                # unit tests
```

### NTA API key
Sign up at https://developer.nationaltransport.ie/signup and subscribe to "GTFS-Realtime"
(free; allow ~15 minutes to activate). A second, unlinked subscription is optional — with two
keys the server alternates between them and polls NTA every 30s instead of 60s.

### Endpoints
All except `/health` require an `X-Api-Key` header matching `SERVER_API_KEY`.
- `GET /health` — liveness check, no auth.
- `GET /arrivals?stopId=<id>` — cached live arrivals for a stop.
- `GET /stops?query=<text>` — stop name/code search over the bundled static GTFS.

### Exposing it publicly
This repo doesn't assume any particular tunnel/proxy. Whatever you put in front of it
(Cloudflare Tunnel, a reverse proxy, a VPN, etc.), the `X-Api-Key` check is what actually
protects the data — treat that key as a secret, and use https in front of it so it isn't sent
in cleartext.

## Notes
- Static GTFS is filtered to Dublin Bus, Bus Éireann and Go-Ahead Ireland (the operators the
  GTFS-Realtime feed covers). Re-run `./scripts/fetch-gtfs-data.sh` to refresh it.
- Single Gradle module, no shared build with the app repo — this is intentionally standalone.

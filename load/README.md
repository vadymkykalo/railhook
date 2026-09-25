# Load and soak tests (k6)

Scenarios that drive `POST /api/v1/events` against a running stack and deliver to a mock receiver
(`load/receiver`) that can be made slow, down or failing.

| Script | What it does |
|---|---|
| `ingest.js` | Sustained ingestion at `TARGET_RPS` |
| `fanout.js` | One event to `FANOUT_N` endpoints |
| `failure-recovery.js` | Endpoint goes slow, then down, then recovers, under traffic |
| `ordering.js` | Ordered deliveries while a retry backlog builds; fails on any out-of-order arrival |
| `soak.js` | Hours of moderate ingestion, for leaks |

Each script's `setup()` registers its own user, project and API key. Env vars are in
`lib/config.js` and at the top of each script.

## Setup

1. Install k6 (https://k6.io/docs/get-started/installation/).
2. Add `WEBHOOK_ALLOW_PRIVATE_IPS=true` to `.env` before `make up`: the receiver has a private
   Docker address, which SSRF protection blocks. Never set this in production; the API refuses to
   start with it when `APP_ENV=production`.
3. Start the stack and the receiver:

   ```bash
   make up && make wait-healthy
   docker compose -f docker-compose.yml -f load/docker-compose.load.yml \
     --profile embedded-db up -d load-receiver
   curl http://localhost:9000/_control/health   # {"ok":true}
   ```

## Running a scenario

```bash
k6 run -e TARGET_RPS=200 -e DURATION=5m load/ingest.js
k6 run -e FANOUT_N=100 -e EVENTS_TO_SEND=10 load/fanout.js
k6 run -e PHASE_HEALTHY_SECONDS=60 -e PHASE_DOWN_SECONDS=120 load/failure-recovery.js
k6 run -e BURST_SIZE=50 -e RETRY_WAIT_SECONDS=150 load/ordering.js
node --test load/receiver/server.test.js
```

### Reading results

- Ingestion: k6 `http_reqs` rate against `TARGET_RPS`, and whether `ingest_errors` or 429s climb.
- End-to-end latency: `GET http://localhost:9000/_control/summary` returns `latencyMsP50` and
  `latencyMsP99`, measured from `data.sentAtMs`. It includes outbox, Kafka and worker time.
- Outbox backlog: `watch -n5 ./load/scripts/outbox-depth.sh`.
- Ordering: `ordering.js` exits non-zero if sequences arrive out of order or fewer than
  `BURST_SIZE` arrive. Give `RETRY_WAIT_SECONDS` room for the first retry at maximum jitter plus
  one retry poll.

## Soak run

```bash
k6 run -e DURATION=4h -e TARGET_RPS=10 load/soak.js &
./load/scripts/monitor-soak.sh soak-results.csv   # samples every 60s (INTERVAL_SECONDS)
```

Look for:

- Connection leak: `*_hikari_active` rising with flat load, or
  `docker compose logs api worker | grep -i "connection leak"`.
- Memory: `*_jvm_used_mb` with a rising floor after GC.
- Redis: `redis_dbsize` that keeps growing after traffic drops. All expected keys have TTLs.

## Target numbers

No numbers have been measured on real hardware yet. Run the scenarios on a machine not shared
with other work and fill this in.

| Metric | Observed | Conditions |
|---|---|---|
| Events ingested/sec | | RPS, VUs, hardware |
| Deliveries/sec | | endpoint count, ordering on/off |
| p99 end-to-end latency | | healthy endpoint, no backlog |
| Outbox backlog onset | | RPS at which the outbox stops draining |

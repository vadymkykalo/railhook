# Load & soak harness (k6)

Nothing measured this platform's throughput or failure behaviour before this
directory existed. These scripts fill that gap: four scenarios plus a soak runner, all driving
the real ingestion API (`POST /api/v1/events`) and a controllable mock
"customer server" (`load/receiver`) standing in for the far end of a webhook
delivery.

## Layout

```
load/
  lib/config.js            env-var config shared by every scenario
  lib/setup.js              register/project/api-key/endpoint/subscription bootstrap
  receiver/server.js         controllable webhook target (healthy/slow/down, fail-next, received log)
  docker-compose.load.yml    adds load-receiver to the webhook-network
  ingest.js                  sustained ingestion at target RPS
  fanout.js                  1 event -> N deliveries
  failure-recovery.js        endpoint goes slow -> down -> recovers, under live traffic
  ordering.js                ordered deliveries under an induced-retry backlog
  soak.js                    hours-long moderate ingestion, for leak-hunting
  scripts/monitor-soak.sh    polls connection pool / JVM memory / outbox depth / Redis size
  scripts/outbox-depth.sh    one-shot outbox backlog snapshot
```

## Setup

1. Install k6. This repo has no k6 in its dev-container; grab a static binary:
   ```bash
   curl -sL https://github.com/grafana/k6/releases/download/v2.2.0/k6-v2.2.0-linux-amd64.tar.gz | tar xz
   ./k6-v2.2.0-linux-amd64/k6 version
   ```
   (or `brew install k6` / the grafana apt repo — see https://k6.io/docs/get-started/installation/)

2. **Allow the load-receiver's private address.** It resolves to a Docker-bridge
   IP, which `SsrfProtectionCustomizer` blocks by default (see the
   `WEBHOOK_ALLOW_PRIVATE_IPS` comment in `.env.dist`). Before `make up`, add
   to your `.env`:
   ```
   WEBHOOK_ALLOW_PRIVATE_IPS=true
   ```
   **Never set this in production** — `ProductionSafetyValidator` will refuse
   to start if `APP_ENV=production` and this is `true`, which is the point.

3. Bring up the platform, then the receiver:
   ```bash
   make up && make wait-healthy
   docker compose -f docker-compose.yml -f load/docker-compose.load.yml \
     --profile embedded-db up -d load-receiver
   curl http://localhost:9000/_control/health   # {"ok":true}
   ```

## Running a scenario

```bash
k6 run load/ingest.js
k6 run -e TARGET_RPS=200 -e DURATION=5m load/ingest.js

k6 run load/fanout.js
k6 run -e FANOUT_N=100 -e EVENTS_TO_SEND=10 load/fanout.js

k6 run load/failure-recovery.js
k6 run -e PHASE_HEALTHY_SECONDS=60 -e PHASE_DOWN_SECONDS=120 load/failure-recovery.js

k6 run load/ordering.js
k6 run -e BURST_SIZE=50 -e RETRY_WAIT_SECONDS=90 load/ordering.js
```

Every script is self-contained: `setup()` registers its own throwaway
user+org+project+API key (see `load/lib/setup.js`), so scripts don't collide
with each other or need any fixture data. Full list of env vars: `load/lib/config.js`
and the comment block at the top of each scenario file.

### Reading results

- **Ingestion throughput**: k6's own `http_reqs` / `iterations` rate against
  `TARGET_RPS` — did the API keep accepting at the requested rate, or did
  `ingest_errors` / 429s climb?
- **Delivery throughput and p99 latency**: each event carries
  `data.sentAtMs`; `load-receiver` computes `receivedAtMs - sentAtMs` per
  delivery and reports `latencyMsP50` / `latencyMsP99` from
  `GET http://localhost:9000/_control/summary`. This is an end-to-end proxy
  (outbox delay + Kafka + worker + HTTP), not a pure HTTP-call latency — call
  that out when you record numbers.
- **Outbox backlog**: `./load/scripts/outbox-depth.sh` any time, or watch it
  climb during a run with `watch -n5 ./load/scripts/outbox-depth.sh`.
- **Ordering**: `load/ordering.js`'s own threshold (`ordering_violations ==
  0`) fails the `k6 run` (non-zero exit) if the receiver saw sequence numbers
  arrive out of order — see `load/ordering.js`'s header comment for exactly
  how it reproduces the backlog condition.

## Soak run

```bash
k6 run -e DURATION=4h -e TARGET_RPS=10 load/soak.js &
./load/scripts/monitor-soak.sh soak-results.csv
```

`monitor-soak.sh` samples every 60s (`INTERVAL_SECONDS` to change it):
HikariCP active/pending connections (api + worker), JVM heap used, outbox
pending count + oldest-pending age, and Redis `DBSIZE`. Look for:

- **Connection leak**: `*_hikari_active` trending up over hours with load
  held flat, or `hikaricp.connections.active` count that never returns to
  baseline between samples. `leak-detection-threshold: 60000` (both
  `application.yml`s) additionally logs a `WARN` in the api/worker container
  logs directly if a connection is checked out longer than 60s —
  `docker compose logs api worker | grep -i "connection leak"` is the
  authoritative check, the CSV trend is the early-warning signal.
- **Memory growth**: `*_jvm_used_mb` climbing without plateauing (a healthy
  JVM saws up and down with GC; a leak looks like a rising floor).
- **Redis key accumulation**: `redis_dbsize` growing unboundedly. Expected
  keys (rate-limit buckets, idempotency keys, ordering cursors, concurrency
  permits) all carry TTLs — a rising floor after traffic returns to baseline
  points at something not expiring. `docker exec webhook-redis redis-cli -a
  "$REDIS_PASSWORD" --scan --pattern 'seq:endpoint:*' | wc -l` (and similarly
  for other prefixes) narrows down which key family is accumulating.

## What these scenarios have been verified against

**No throughput, latency or soak numbers are published yet**, and none are
claimed below. What follows is the harness proving itself, which is a different
and smaller claim: that a red run means a real regression rather than a broken
script.

Running the scenarios against the real stack needs a machine that is not also
running the test suite. `docker-compose.yml` uses fixed container names
(`webhook-postgres`, `webhook-kafka`, ...), so a `make up` alongside a
Testcontainers run competes with it for both names and memory, and any number
measured under that contention describes the contention.

Every scenario (`ingest.js`, `fanout.js`, `failure-recovery.js`, `ordering.js`,
`soak.js`) has been run end-to-end with `k6 run` against a minimal stand-in API
(`register`/`projects`/`api-keys`/`endpoints`/`subscriptions`/`events`,
fanning out to `load-receiver` with one retry on failure — not committed to
the repo, throwaway) plus the real `load/receiver/server.js`. This confirmed:

- Every script's `setup()` chain (register -> project -> API key -> endpoint
  -> subscription) executes and all checks pass.
- `fanout.js` correctly asserts `deliveriesCreated === FANOUT_N` and the
  receiver's total matches `EVENTS_TO_SEND * FANOUT_N` (verified 3 events x 5
  endpoints = 15/15 delivered).
- `failure-recovery.js`'s phase controller and traffic generator run
  concurrently for the full scheduled duration and both scenarios complete
  cleanly.
- `ordering.js`'s threshold does what it's supposed to: against the stand-in
  (which has no per-endpoint ordering buffer at all) it correctly detected
  and reported out-of-order arrivals and **failed the k6 run** (`exit 99`,
  `thresholds on metrics 'ordering_violations' have been crossed`) — i.e. the
  scenario is a real regression check, not just a log line. It has not yet
  been run against the actual `OrderingBufferService`; the Redis-flush drill
  in `DeliveryEndToEndIntegrationTest` exercises that code directly instead.
- `soak.js` runs under `constant-arrival-rate` and reports a receiver summary
  in teardown. The four-hour run is what it says it is and has not been done.

**To get real numbers**: run the "Setup" and "Running a scenario" sections
above against an actual `make up` stack on a machine not shared with other
work, and fill in the table below. Until it has rows, the platform's
performance is an assertion — which is the one claim a delivery platform
cannot make on prose alone.

### Target numbers (fill in from a real run)

| Metric | Target | Observed | Conditions |
|---|---|---|---|
| Events ingested/sec | ? | *(run `load/ingest.js` and record)* | RPS, VU count, hardware |
| Deliveries/sec | ? | | endpoint count, ordering on/off |
| p99 end-to-end delivery latency | ? | | healthy endpoint, no backlog |
| Outbox backlog onset | ? | | RPS at which `outbox_pending` starts climbing rather than draining |

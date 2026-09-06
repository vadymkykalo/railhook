# Railhook Monitoring Stack

**Prometheus + Alertmanager + Grafana + Loki/Promtail** — fully pre-configured, decoupled from the main platform.

## Quick Start

```bash
# From project root:
make monitoring-up

# Open Grafana:
#   http://localhost:3001
#   Login: railhook / railhook_monitor_2024
```

## What's Included

### Prometheus
- **Scrapes:** API (`:8082/actuator/prometheus`), Worker (`:8081/actuator/prometheus`) — see
  "Metrics-scrape auth" below for why these are *not* 8080/the app's main port
- **Alert rules:** 15 rules in `prometheus/alerts.yml` — retry backlog (4), DLQ (3),
  retry governor (2), circuit breaker (2), API error rate (1), oldest-pending-age (2),
  outbox SENDING stuck (1, P1-24c)
- **Retention:** 30 days
- **Port:** 9090 (localhost only)

### Alertmanager

Routes the 15 `alerts.yml` rules to a real receiver — Prometheus firing an alert
with nowhere to send it is a red square on a dashboard nobody is watching during
the incident, which was the state of this repo before Alertmanager was wired up.

- **Port:** 9093 (localhost only) — UI at http://localhost:9093
- **Config:** rendered at container start by `alertmanager/render-config.sh` from
  the `ALERTMANAGER_*` env vars (see `.env.dist` "ALERTING" section) — Slack,
  a generic webhook (PagerDuty/Opsgenie/custom), and/or email. Leave all unset
  and Alertmanager still starts; alerts just have nowhere to go but its own
  UI/API, which is the "you haven't configured a receiver yet" state, not a
  crash. (The image is busybox-based with no `envsubst`/`apk`/bash, which is why
  this is a POSIX-sh script instead of a static YAML file with a templating
  sidecar — see the script's header.)
- **Routing:** grouped by `alertname` + `component`; `severity: critical` gets
  its own faster-paging route (`group_wait: 10s`, `repeat_interval: 1h`),
  `warning` and `info` route separately with longer repeat intervals.
- **Inhibition:** a firing `*Critical` alert suppresses the corresponding
  `*High`/`*Growing`/`*Stale` warning for the same `component` — 15 rules firing
  ungrouped at 3am is its own failure mode. See `alertmanager/render-config.sh`
  for the exact rules.
- **Verified live**: a synthetic `DeliveryPendingBacklogCritical` alert
  was POSTed to Alertmanager's `/api/v2/alerts`, routed to the `railhook-critical`
  receiver, and delivered as a webhook POST to a throwaway HTTP listener —
  payload received, 200 OK. A paired `DeliveryPendingBacklogHigh` (warning, same
  component) was correctly suppressed (`"state":"suppressed","inhibitedBy":[...]`
  in `/api/v2/alerts`).

### Metrics-scrape auth

`SecurityConfig.java` requires a JWT or API-key on `/actuator/**` (beyond
`health`/`health/**`/`info`), but Prometheus can't authenticate that way — it
would get a 401 scraping `/actuator/prometheus` on the app's main port.

**Fix (Compose, this stack):** the API and worker both split actuator onto a
separate `management.server.port` (API: 8082, worker: 8081) via
`MANAGEMENT_PORT`/`MANAGEMENT_ADDRESS`. When `management.server.port` differs
from `server.port`, Spring Boot serves actuator from a second embedded web
server that never passes through the app's `SecurityFilterChain` — so no auth
is required there at all. Neither port is published to the host in
`docker-compose.yml`; both are only reachable from other containers on
`webhook-network`, the same trust boundary Postgres/Kafka/Redis already rely
on. If you widen that network's membership, `/actuator/prometheus` (endpoint
listing, cardinality, in worker's case zero auth on `/actuator/env` too if ever
exposed) becomes reachable to whatever else is on it — that's the residual risk
to weigh, not "wide open to the internet" (the port still isn't published).

**Kubernetes/Helm:** `deploy/helm/railhook/templates/servicemonitor.yaml`
scrapes the `management` port by name, which the chart sets to 8082 (API) and
8081 (worker) — the same split Compose uses. It previously scraped the main
authenticated port and every scrape 401'd, unnoticed because CI linted the
chart but never applied it.

**Bonus fix, not just Compose:** `MANAGEMENT_ADDRESS` defaulted to
`127.0.0.1` — loopback *inside* the worker's own container — which silently
made `worker:8081` unreachable from any other container, including this
Prometheus (scrape always failed) and, in the Helm/K8s deployment, **kubelet's
own liveness/readiness `httpGet` probes**, which also connect from outside the
container's network namespace. That's not a monitoring nice-to-have, that's a
worker pod that can never become `Ready` in a real cluster. Default is now
`0.0.0.0`; the port still isn't published to the host anywhere.

### Logs (P3-36b)

No log aggregation existed before this: `docker-compose.prod.yml` sets `LOG_LEVEL`
and logs go straight to stdout, with no collector and no rotation — a restarted or
rescheduled container's history was simply gone. Meanwhile api/worker already had
all the structured-logging groundwork in place and it was being thrown away:
`JwtAuthenticationFilter`/`ApiKeyAuthenticationFilter` populate MDC with
`organizationId`/`userId`/`projectId`, `CorrelationIdFilter` (api) and
`DeliveryConsumer`/`IncomingForwardConsumer` (worker) populate `correlationId`,
and both modules' `logback-spring.xml` already emit single-line JSON in production
via `LogstashEncoder` — but each `logback-spring.xml` also had an
`<includeMdcKeyName>` allow-list that silently dropped everything except
`correlationId` (api) / a list that didn't even match what the worker code puts
into MDC (worker) from the shipped JSON. Both were fixed as part of this change
(see the two `logback-spring.xml` files) — the encoder now emits the whole MDC map.

- **Loki**: single-node, filesystem-backed (`monitoring/loki/loki-config.yml`).
  Retention via the compactor, `LOKI_RETENTION_PERIOD` (default `336h` / 14 days,
  independent of the Postgres `DATA_RETENTION_*` days — see `.env.dist`).
  Port 3100 (localhost only).
- **Promtail**: discovers containers via the Docker daemon
  (`docker_sd_configs`) and relabels on the `com.docker.compose.service` Docker
  label rather than assuming fixed container names — this repo doesn't set
  `container_name` for api/worker, and is routinely checked out into
  differently-named directories (parallel-agent worktrees, forks, ...), which
  changes Compose's default project name and therefore the generated container
  names. Only ships `api`/`worker` logs (see the `keep` relabel rule in
  `monitoring/promtail/promtail-config.yml` if you want to widen this).
  Extracts `level` as a Loki label from the production JSON logs; per-request/
  per-tenant identifiers (`correlationId`, `organizationId`, `deliveryId`, ...)
  deliberately stay unindexed in the log line body — promoting them to Loki
  labels would blow up index cardinality — and are queried with `| json` /
  a substring filter instead.
- **Grafana**: a `Loki` datasource is auto-provisioned alongside `Prometheus`
  (`monitoring/grafana/provisioning/datasources/datasource.yml`), and the new
  **Railhook — Logs** dashboard below gives a starting point for pivoting on
  `correlationId`/`organizationId`.

### Grafana Dashboards

| Dashboard | Description |
|---|---|
| **Railhook — Overview** | Events ingested, delivery pipeline, queue depth, DLQ, table sizes, billing reconciliation, error rates |
| **Railhook — Worker & Circuit Breaker** | Circuit breaker trips/rejects/slow-trips, retry governor, async pool threads, queue depths |
| **Railhook — JVM & Micrometer** | Heap memory, GC pauses, threads, HTTP request rates & latency percentiles, HikariCP pool, CPU |
| **Railhook — Kafka** | Consumer lag by topic/partition, records consumed rate, fetch latency, producer queue time |
| **Railhook — Logs** | Logs panel + volume-by-level, with `correlation_id`/`organization_id` template variables for pivoting (P3-36b) |

### Auto-provisioned
- Prometheus + Loki datasources (no manual setup needed)
- All 5 dashboards loaded on first boot
- Home dashboard: Railhook Overview

## Configuration

All config is via environment variables (defaults in `docker-compose.yml`):

| Variable | Default | Description |
|---|---|---|
| `GF_ADMIN_USER` | `railhook` | Grafana admin username |
| `GF_ADMIN_PASSWORD` | `railhook_monitor_2024` | Grafana admin password |
| `GRAFANA_PORT` | `3001` | Grafana external port |
| `ALERTMANAGER_SLACK_WEBHOOK_URL` | _(unset)_ | Slack incoming-webhook URL |
| `ALERTMANAGER_SLACK_CHANNEL` | `#railhook-alerts` | Slack channel |
| `ALERTMANAGER_WEBHOOK_URL` | _(unset)_ | generic webhook receiver (PagerDuty/Opsgenie/custom) |
| `ALERTMANAGER_EMAIL_TO` / `_FROM` / `_SMTP_HOST` / `_SMTP_PORT` | _(unset)_ / `alerts@example.com` / `localhost` / `1025` | email receiver |
| `LOKI_RETENTION_PERIOD` | `336h` (14d) | how long Loki keeps ingested logs |

To override, create a `.env` file in `monitoring/` or pass env vars:

```bash
GF_ADMIN_PASSWORD=my_secret_password make monitoring-up
```

## Commands

```bash
make monitoring-up      # Start Prometheus + Alertmanager + Grafana
make monitoring-down    # Stop monitoring stack
make monitoring-logs    # Follow monitoring logs (all three services)
```

To smoke-test the alerting path without waiting for a real threshold breach,
POST a synthetic alert straight to Alertmanager (it doesn't care whether the
alert came from Prometheus's rule evaluation or the API — the payload it hands
to receivers is identical either way):

```bash
curl -s -XPOST http://localhost:9093/api/v2/alerts -H 'Content-Type: application/json' -d '[{
  "labels": {"alertname": "DeliveryPendingBacklogCritical", "severity": "critical", "component": "worker"},
  "annotations": {"summary": "test", "description": "synthetic alert"},
  "startsAt": "'"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"'"
}]'
curl -s http://localhost:9093/api/v2/alerts | jq .
```

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                       Docker Network                            │
│                webhook-platform_webhook-network                 │
│                                                                  │
│  ┌─────────┐   ┌──────────┐   ┌───────────────┐  ┌────────────┐│
│  │   API    │   │  Worker   │   │  Prometheus   │  │Alertmanager││
│  │ :8080    │   │ :8081    │◄──│    :9090      │─►│   :9093    ││
│  │ mgmt:8082│◄──┤(mgmt port)│  └───────┬───────┘  └─────┬──────┘│
│  └────┬────┘   └────┬─────┘           │                │       │
│       │(stdout)      │(stdout)  ┌───────▼───────┐   Slack/webhook│
│       ▼              ▼          │   Grafana     │   /email       │
│  ┌──────────────────────┐       │   :3001       │   (ALERTMANAGER_*)
│  │       Promtail        │──┐   └───────▲───────┘                │
│  │ docker_sd_configs,    │  │           │                        │
│  │ api+worker logs only  │  │  reads Loki + Prometheus            │
│  └───────────────────────┘  │           │                        │
│                              ▼           │                        │
│                        ┌──────────┐      │                        │
│                        │   Loki    │──────┘                        │
│                        │  :3100    │                               │
│                        └──────────┘                               │
└──────────────────────────────────────────────────────────────────┘
```

Monitoring connects to the platform's existing Docker network as an
**external** network — no changes needed there. The main `docker-compose.yml`
*was* changed: the API gained a dedicated `MANAGEMENT_PORT` (8082) so
Prometheus can scrape it without authenticating, and the worker's
`MANAGEMENT_ADDRESS` default changed from `127.0.0.1` to `0.0.0.0` so it's
reachable at all. See "Metrics-scrape auth" above.

## Production (Kubernetes)

For Kubernetes deployments, use the Helm chart values or deploy kube-prometheus-stack:

```bash
helm install monitoring prometheus-community/kube-prometheus-stack \
  --set prometheus.prometheusSpec.additionalScrapeConfigs[0].job_name=railhook-api \
  --set prometheus.prometheusSpec.additionalScrapeConfigs[0].metrics_path=/actuator/prometheus \
  --set prometheus.prometheusSpec.additionalScrapeConfigs[0].static_configs[0].targets[0]=railhook-api:8080
```

The Grafana dashboard JSONs in `monitoring/grafana/dashboards/` can be imported directly into any Grafana instance.

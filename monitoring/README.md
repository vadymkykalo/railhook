# Railhook Monitoring Stack

Prometheus, Alertmanager, Grafana, Loki/Promtail, node-exporter, cAdvisor and blackbox-exporter,
preconfigured for Railhook and run as a second Compose project beside the platform. Optional:
nothing starts until you start it.

## Quick start

On an `install.sh` deployment (`/opt/railhook`):

```bash
cd /opt/railhook
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
echo "ALERTMANAGER_EMAIL_TO=you@example.com" >> .env     # optional
./railhook monitoring up
ssh -L 3001:127.0.0.1:3001 you@your-host                 # then http://localhost:3001, user admin
```

`monitoring up` fetches this directory for the release the host runs (the file list lives in the
helper and a test holds it equal to this directory), then starts the stack. `./railhook upgrade`
refreshes it and restarts it if it was running.

From a clone:

```bash
make up
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
make monitoring-up                                        # http://localhost:3001
```

There is no default Grafana password. Grafana refuses to start with an empty one, a known default,
or anything under 16 characters, and re-applies the one in `.env` on every start — change it
there and run `monitoring up` again.

## Grafana on a domain

Set `MONITORING_DOMAIN=grafana.example.com` in `.env`, point DNS at the host and run
`./railhook monitoring up`. The helper asks `install.sh --refresh` to rewrite the Caddyfile, which
adds a site block proxying to `railhook-grafana:3000`, and reloads Caddy. Caddy obtains the
certificate as it does for the platform. With `--behind-proxy`, point your own proxy at
`127.0.0.1:3001` instead.

Put an identity-aware proxy (Cloudflare Access, for one) in front of that host name. If it covers
every path, give `/.well-known/acme-challenge/` a bypass, or Caddy cannot renew the certificate.

## Commands

```bash
./railhook monitoring up              # start, or apply .env changes
./railhook monitoring status          # containers, and every Prometheus target's health
./railhook monitoring logs [service]
./railhook monitoring down            # stop; metrics and logs stay in the volumes
```

`make monitoring-up`, `make monitoring-down` and `make monitoring-logs` do the same in a clone.

Both pin the project name to `railhook-monitoring` and read the platform's `.env`
(`--env-file`). The pin matters: that `.env` may set `COMPOSE_PROJECT_NAME` to the platform's
own name, and a `down` under it would stop the platform.

## What runs

| Service | Image | Memory limit | Reachable from |
|---|---|---|---|
| grafana | `grafana/grafana:12.1.1` | 320m | `127.0.0.1:3001`; Caddy over the platform network |
| prometheus | `prom/prometheus:v2.51.2` | 384m | `monitoring` network; platform network (to scrape) |
| alertmanager | `prom/alertmanager:v0.27.0` | 64m | `monitoring` network |
| loki | `grafana/loki:3.0.0` | 320m | `monitoring` network |
| promtail | `grafana/promtail:3.0.0` | 96m | `monitoring` network; Docker socket read-only |
| node-exporter | `prom/node-exporter:v1.9.1` | 64m | `monitoring` network; host `/` read-only |
| cadvisor | `gcr.io/cadvisor/cadvisor:v0.52.1` | 192m | `monitoring` network; `/sys`, Docker socket and `/var/lib/docker` read-only |
| blackbox | `prom/blackbox-exporter:v0.27.0` | 32m | `monitoring` network; platform network (to probe) |
| backup-age | `busybox:1.36.1` | 8m | no network; backup directory read-only |

The limits total 1480 MB. Only Grafana publishes a port. Prometheus, Alertmanager and Loki are
reached through Grafana (Explore, Alerting — Alertmanager is a provisioned datasource) or
`docker exec`.

### Prometheus

- **Scrapes:** API (`api:8082/actuator/prometheus`), worker (`worker:8081/actuator/prometheus`),
  node-exporter, cAdvisor (named containers only), the blackbox probes, and the stack itself.
- **Retention:** `PROMETHEUS_RETENTION` (default `15d`), capped at `PROMETHEUS_RETENTION_SIZE`
  (default `4GB`).
- **Rules:** `prometheus/alerts.yml` is the platform's set and is held equal to the Helm chart's
  `PrometheusRule` by `AlertRuleParityTest`. `prometheus/host-alerts.yml` is this host's:

  | Group | Alerts |
  |---|---|
  | host | `HostExporterDown`, `HostDiskAlmostFull` (>85%), `HostDiskCritical` (>95%), `HostMemoryHigh` (>90%), `HostOomKill`, `HostLoadHigh` (15m load > 1.5× cores) |
  | containers | `ContainerRestarting` (≥2 restarts in 15m), `ContainerOomKilled`, `ContainerMemoryNearLimit` (>90%) |
  | uptime | `PublicEndpointDown`, `PublicEndpointSlow` (>3s), `UiDown`, `CaddyDown`, `TlsCertificateExpiringSoon` (<14d), `TlsCertificateExpiryImminent` (<3d) |
  | backups | `BackupStale` (newest dump >26h old), `BackupMissing` |
  | monitoring | `MonitoringComponentDown`, `AlertmanagerNotificationsFailing` |

- **Probe targets** are rendered at start by `prometheus/render-targets.sh`, because Prometheus
  does not expand env in its config: `MONITORING_PROBE_URLS` (comma-separated), or, when empty,
  `https://$RAILHOOK_DOMAIN/`, `/docs/` and `/actuator/health`. Inside the network it always
  probes `http://ui:5173/`, and `caddy:443` when there is a domain. Behind Cloudflare the TLS
  expiry is the edge certificate's; an expired origin certificate fails the probe itself (526).

### Alertmanager

- **Config:** rendered at container start by `alertmanager/render-config.sh` from `ALERTMANAGER_*`
  (the image is busybox: no `envsubst`, no bash). It never prints the rendered file, which holds
  the SMTP password and the Telegram token — only which receivers are on.
- **Receivers:** Slack, a generic webhook, email, Telegram — each on when its variables are set.
  Email defaults to the platform's own relay: `EMAIL_FROM`, `SMTP_HOST`, `SMTP_PORT`,
  `SMTP_USERNAME`, `SMTP_PASSWORD`, unless the `ALERTMANAGER_SMTP_*` equivalents are set. TLS is
  required except for `localhost`, `mailpit` and `mailhog`.
- **Routing:** grouped by `alertname` + `component`; `critical` pages faster (`group_wait: 10s`,
  `repeat_interval: 1h`).
- **Inhibition:** a critical tier suppresses its warning tier (backlog, oldest pending, disk, TLS),
  and `UiDown` suppresses the public probe alerts it explains.

To test the path without waiting for a threshold, post a synthetic alert from inside the network:

```bash
docker exec railhook-alertmanager amtool alert add DeliveryPendingBacklogCritical \
  severity=critical component=worker --annotation=summary=test \
  --alertmanager.url=http://localhost:9093
```

### Logs

- **Promtail** discovers containers through the Docker socket and keeps `api`, `worker`, `ui`,
  `caddy` and `db-backup` by their `com.docker.compose.service` label, so the platform's project
  name does not matter. It extracts `level` as a label; per-request identifiers (`correlationId`,
  `organizationId`, …) stay in the line and are filtered at query time, because as labels they
  would explode Loki's index.
- **Loki** is single-node on the filesystem; the compactor deletes lines older than
  `LOKI_RETENTION_PERIOD` (default `168h`, 7 days).

### Backups

`backup-age` reads the `webhook_platform_*.dump` files the `db-backup` sidecar writes (from
`MONITORING_BACKUP_DIR`, default `../backups`, i.e. the platform's `backups/`) every five minutes
and writes their count and the newest one's age and size as node-exporter textfile metrics. It
measures what a restore would need — a file — not whether a script ran.

### Dashboards

| Dashboard | What it answers |
|---|---|
| **Railhook — Overview** (home) | Public site up, firing alerts, disk/memory/CPU, backup age; then events, deliveries, queues, DLQ, errors |
| **Host (Node Exporter Full)** | Everything node-exporter knows about the host. Vendored, Apache-2.0 — see `grafana/NOTICE` |
| **Containers** | CPU, memory against the Compose limit, network and restarts per container |
| **Uptime** | Probe status and uptime over the range, response time by phase, HTTP status, TLS days left, backups |
| **Logs** | Filter by service, level (case-insensitive: the JVMs write `ERROR`, Caddy `error`), text, correlation or organization ID |
| **Railhook — Worker & Circuit Breaker** | Circuit breaker, retry governor, async pools, queue depths |
| **Railhook — JVM & Micrometer** | Heap, GC, threads, HTTP latency, HikariCP |
| **Railhook — Kafka** | Consumer lag, consume rate, fetch latency |

All are provisioned from `grafana/dashboards/` and read-only in the UI; change the JSON.

## Configuration

Every variable is documented in `.env.dist` under "MONITORING STACK", "ALERTING" and "LOG
AGGREGATION".

## Metrics-scrape auth

`SecurityConfig.java` requires a JWT or API key on `/actuator/**` (beyond `health`/`info`), which
Prometheus cannot present. The API and worker therefore serve actuator on a separate
`management.server.port` (API 8082, worker 8081, `MANAGEMENT_PORT`/`MANAGEMENT_ADDRESS`). A
different management port runs a second embedded server outside the app's `SecurityFilterChain`,
so it needs no credentials. Neither port is published by `docker-compose.yml`; both are reachable
only from containers on the platform network — which this stack joins, and which is the trust
boundary Postgres, Kafka and Redis already rely on.

`MANAGEMENT_ADDRESS` defaults to `0.0.0.0`. Loopback inside the container made the worker's port
unreachable from Prometheus and from kubelet's probes alike.

In Kubernetes, `deploy/helm/railhook/templates/servicemonitor.yaml` scrapes the named
`management` port; turn on the chart's `monitoring.*` values instead of running this stack.

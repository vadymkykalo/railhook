# Monitoring stack

Prometheus, Alertmanager, Grafana, Loki/Promtail, node-exporter, cAdvisor and blackbox-exporter,
set up for Railhook. It runs as a separate Compose project next to the platform and is optional.

## Start

On an `install.sh` deployment:

```bash
cd /opt/railhook
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
echo "ALERTMANAGER_EMAIL_TO=you@example.com" >> .env     # optional
v=$(grep '^API_IMAGE_TAG=' .env | cut -d= -f2)
curl -fsSL "https://github.com/vadymkykalo/railhook/archive/refs/tags/v${v}.tar.gz" \
  | tar xz --strip-components=1 --wildcards '*/monitoring/*'
docker compose -p railhook-monitoring --env-file .env -f monitoring/docker-compose.yml up -d
ssh -L 3001:127.0.0.1:3001 you@your-host                 # then http://localhost:3001, user admin
```

From a clone: `make up`, add `GRAFANA_ADMIN_PASSWORD` to `.env`, then `make monitoring-up`.

- There is no default Grafana password. Grafana refuses an empty or known one, or one under 16
  characters. It re-applies the password from `.env` on every start.
- After `./railhook upgrade`, download `monitoring/` for the new release and run `up -d
  --force-recreate`. A plain `up -d` keeps the old rules and dashboards.
- The stack joins `railhook_webhook-network`. If the install directory is not named `railhook`,
  set `RAILHOOK_NETWORK` in `.env`.

## Commands

```bash
mon="docker compose -p railhook-monitoring --env-file .env -f monitoring/docker-compose.yml"
$mon up -d        # start, or apply .env changes
$mon ps
$mon logs -f [service]
$mon down         # data stays in the volumes
```

Always pass `-p railhook-monitoring`: the platform's `.env` may set `COMPOSE_PROJECT_NAME`, and a
`down` under that name stops the platform. `make monitoring-up`, `monitoring-down` and
`monitoring-logs` do the same in a clone.

## Grafana on a domain

Set `MONITORING_DOMAIN=grafana.example.com` in `.env`, point DNS at the host and run
`curl -fsSL https://railhook.io/install.sh | bash -s -- --refresh --dir /opt/railhook`. Caddy gets
a site block for Grafana and a certificate. With `--behind-proxy`, point your proxy at
`127.0.0.1:3001`. If an access proxy covers the host, let `/.well-known/acme-challenge/` through.

## What runs

| Service | Memory | Notes |
|---|---|---|
| grafana | 320m | The only published port, `127.0.0.1:3001` |
| prometheus | 384m | Retention `PROMETHEUS_RETENTION` (15d), `PROMETHEUS_RETENTION_SIZE` (4GB) |
| alertmanager | 64m | Slack, webhook, email, Telegram; each on when its `ALERTMANAGER_*` variables are set |
| loki, promtail | 320m, 96m | Logs of `api`, `worker`, `ui`, `caddy`, `db-backup`; kept `LOKI_RETENTION_PERIOD` (168h) |
| node-exporter, cadvisor | 64m, 192m | Host and container metrics |
| blackbox | 32m | Probes `MONITORING_PROBE_URLS`, or `https://$RAILHOOK_DOMAIN/`, `/docs/`, `/actuator/health` |
| backup-age | 8m | Age and size of the newest `webhook_platform_*.dump` in `MONITORING_BACKUP_DIR` |

Alert rules: `prometheus/alerts.yml` (the platform's, kept equal to the Helm chart's
`PrometheusRule` by `AlertRuleParityTest`) and `prometheus/host-alerts.yml` (disk, memory, OOM,
container restarts, public endpoint down or slow, TLS expiry, stale or missing backup).
Dashboards are provisioned from `grafana/dashboards/` and are read-only in the UI; edit the JSON.

Email alerts use the platform's SMTP settings unless `ALERTMANAGER_SMTP_*` is set. To test the
alert path:

```bash
docker exec railhook-alertmanager amtool alert add DeliveryPendingBacklogCritical \
  severity=critical component=worker --annotation=summary=test \
  --alertmanager.url=http://localhost:9093
```

Every variable is documented in `.env.dist` under "MONITORING STACK", "ALERTING" and
"LOG AGGREGATION".

## Metrics port

`/actuator/prometheus` on the main port requires auth. The API and worker serve actuator on a
separate management port (API 8082, worker 8081) that needs none. Neither port is published; only
containers on the platform network reach them. `MANAGEMENT_ADDRESS` defaults to `0.0.0.0`, because
loopback made the worker's port unreachable for Prometheus and kubelet probes.

On Kubernetes, use the chart's `monitoring.serviceMonitor` instead of this stack.

# Railhook — Self-Hosted Deployment Guide

> Complete guide for deploying Railhook in your own infrastructure.

---

## Table of Contents

1. [Hardware Requirements](#1-hardware-requirements)
2. [Network Requirements](#2-network-requirements)
3. [Pre-flight Checks](#3-pre-flight-checks)
4. [Installation Methods](#4-installation-methods)
5. [Configuration Reference](#5-configuration-reference)
6. [Upgrade Strategy](#6-upgrade-strategy)
7. [Backup & Restore](#7-backup--restore)
8. [TLS & mTLS](#8-tls--mtls)
9. [Monitoring](#9-monitoring)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Hardware Requirements

### Sizing Guide

| Profile | Events/day | API Pods | Worker Pods | PostgreSQL | Kafka | Redis | Total RAM |
|---------|-----------|----------|-------------|------------|-------|-------|-----------|
| **Small** | < 10K | 2 × 512Mi | 2 × 512Mi | 1 × 1Gi | 1 × 1Gi | 1 × 256Mi | ~5 Gi |
| **Medium** | < 100K | 3 × 1Gi | 3 × 1.5Gi | Primary + replica, 2Gi each | 3 × 2Gi | Sentinel, 512Mi each | ~20 Gi |
| **Large** | < 1M | 5 × 2Gi | 10 × 2Gi | Primary + 2 replicas, 4Gi each | 5 × 4Gi | Cluster (3 nodes), 1Gi each | ~65 Gi |

### Disk Requirements

| Component | Small | Medium | Large |
|-----------|-------|--------|-------|
| PostgreSQL | 10 Gi | 50 Gi | 200 Gi (SSD) |
| Kafka | 10 Gi | 50 Gi | 200 Gi (SSD) |
| Redis | 1 Gi | 2 Gi | 5 Gi |
| Backups | 10 Gi | 50 Gi | 200 Gi |

### CPU Requirements

- **API:** 250m-1000m per pod (bursty: JSON parsing, encryption)
- **Worker:** 500m-2000m per pod (sustained: HTTP calls, retry scheduling)
- **PostgreSQL:** 250m-2000m (I/O bound, benefits from fast storage)
- **Kafka:** 500m-2000m per broker (network + disk I/O)

---

## 2. Network Requirements

### Ports

| Service | Port | Protocol | Direction | Notes |
|---------|------|----------|-----------|-------|
| Web | 80 (443 with `--domain`) | TCP | Inbound | **The only port published.** Dashboard, REST API, ingress and tunnels, all through nginx |
| API (HTTP) | 8080 | TCP | Internal | Reached through nginx; not bound to the host by the installer |
| API (Actuator) | 8082 | TCP | Internal | Health and metrics on their own port, deliberately outside the JWT/API-key chain so Prometheus can scrape without credentials. Never published — nginx proxies the two health paths and nothing else |
| Worker (Actuator) | 8081 | TCP | Internal | Health checks + metrics |
| UI (HTTP) | 80/443 | TCP | Inbound | Web dashboard |
| PostgreSQL | 5432 | TCP | Internal | Database |
| Kafka | 9092 | TCP | Internal | Message bus |
| Redis | 6379 | TCP | Internal | Cache + rate limiting |

### Egress Requirements

- **Worker → Internet:** Must be able to reach webhook endpoints (HTTP/HTTPS on arbitrary ports)
- **API → Kafka:** For outbox publishing
- **Worker → Kafka:** For consuming deliveries
- **API/Worker → PostgreSQL:** For persistence
- **API/Worker → Redis:** For caching and rate limiting
- **API → Stripe/WayForPay:** If billing enabled (HTTPS to `api.stripe.com`, `api.wayforpay.com`)

### SSRF Protection

By default in production, Railhook blocks webhook delivery to private/internal IPs:

```yaml
WEBHOOK_ALLOW_PRIVATE_IPS: "false"  # Always false in production
WEBHOOK_ALLOWED_HOSTS: ""           # Comma-separated allowlist for internal services
```

If you need to deliver webhooks to internal services:
```yaml
WEBHOOK_ALLOWED_HOSTS: "internal-service.default.svc.cluster.local,10.0.1.50"
```

### Reverse Proxy / Trusted Proxies

If Railhook's API sits behind a reverse proxy, load balancer, or ingress controller
(anything that terminates the client connection and forwards to the API), you **must**
tell it which peer to trust, or none of `X-Forwarded-For` / `X-Real-IP` is honoured:

```yaml
WEBHOOK_TRUSTED_PROXIES: "10.0.0.5,172.20.0.0/16"   # your proxy's address or pod/service CIDR
```

This is used for auth rate limiting (login/register/refresh/reset-password), audit
logging, and incoming-webhook source IP checks. The default is **empty — trust
nothing**, which is correct when the API is reached directly (no proxy in front, e.g.
`make up`'s default docker-compose). When `X-Forwarded-For` carries multiple hops (a
chain of proxies), the resolver walks from the right and returns the first hop that
isn't itself in `WEBHOOK_TRUSTED_PROXIES` — never the naive left-most entry, which is
attacker-controlled.

Get this wrong in either direction:
- **Leave it empty behind a real proxy** → every request is bucketed under the proxy's
  one IP; legitimate users behind that proxy share a single rate-limit bucket and can
  lock each other out, and audit logs record the proxy's address instead of the client.
- **Set it too broadly (or trust a peer you don't control)** → an attacker who can reach
  that peer, or spoof its address, can forge `X-Forwarded-For` and get a fresh IP per
  request, defeating rate limiting entirely.

The bundled UI-nginx proxy is what traffic goes through, so set this to
`172.16.0.0/12` by default, matching Docker Compose's default bridge network range —
narrow it to the proxy's actual address if you've pinned a custom subnet.

---

## 3. Pre-flight Checks

Before deploying, verify:

### 3.1 Infrastructure Connectivity

```bash
# PostgreSQL
psql -h $DB_HOST -p $DB_PORT -U $DB_USERNAME -d $DB_NAME -c "SELECT 1;"

# Kafka
kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --list

# Redis
redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD ping
```

### 3.2 Security Configuration

```bash
# Encryption key must not be the default
[ "$WEBHOOK_ENCRYPTION_KEY" = "change-me-to-a-32-char-secret!!" ] && echo "  CHANGE ENCRYPTION KEY"

# JWT secret must be strong (min 32 chars)
[ ${#JWT_SECRET} -lt 32 ] && echo "  JWT_SECRET too short"

# SSRF protection must be enabled
[ "$WEBHOOK_ALLOW_PRIVATE_IPS" = "true" ] && echo "  SSRF protection disabled"
```

### 3.3 Resource Availability

```bash
# Kubernetes: check available resources
kubectl top nodes
kubectl describe nodes | grep -A5 "Allocated resources"
```

---

## 4. Installation Methods

Four of them, and they are not equally recommended. **4.1 is the one to use**
unless you have a specific reason not to; the rest exist for deployments that
have outgrown it.

### 4.1 The installer (recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/install.sh | bash
```

It checks the machine first — Docker, Compose (either spelling), memory, disk,
the port — then writes `~/railhook` containing a Compose file pinned to the
latest release, a `.env` with freshly generated secrets, and a small `railhook`
helper. It verifies the configuration it wrote before starting anything, and
tells you the URL when the platform answers.

What it leaves behind is an ordinary Compose deployment. `docker compose` works
on it exactly as you would expect; nothing about it is bespoke.

**Onto a domain, with HTTPS:**

```bash
curl -fsSL .../install.sh | bash -s -- --domain hooks.example.com --email ops@example.com
```

Point the domain's A record at the server first. That flag brings up a TLS
terminator that obtains and renews the certificate itself — no cron entry, no
renewal hook — moves the dashboard behind it onto loopback, and sets
`APP_ENV=production`, which turns on `ProductionSafetyValidator`. From then on
the platform refuses to start on unsafe configuration rather than running with
it.

**Other options:** `--dir` to install elsewhere, `--port` when 80 is taken,
`--no-start` to write the files and stop, `--check` to re-run the machine and
configuration checks against an existing install, `--uninstall` (keeps data) and
`--purge` (does not). `--help` lists them.

**Day two**, from the install directory:

```bash
./railhook status | logs | stop | start | upgrade | backup | doctor
```

`doctor` re-runs every check against what is on disk, which is how a
hand-edited `.env` gets caught before it becomes an outage.

**Back up `.env`.** `WEBHOOK_ENCRYPTION_KEY` in it is what every endpoint secret
in the database is encrypted with. A database backup without that file restores
rows nothing can read.

**One published port.** The dashboard's nginx is the only thing bound to the
host; it serves the dashboard and proxies `/api/`, `/ws/tunnel`, `/tunnel/`,
`/hook/`, `/ingress/` and the health probes. Postgres, Kafka, Redis, the API and
the actuator port are reachable only from inside the Docker network. One URL,
one certificate, one firewall rule. If you need one of them directly, add a
`docker-compose.override.yml` — the header of the Compose file has the snippet.

### 4.2 Docker Compose, driven by hand

The same file 4.1 installs, run yourself. Worth knowing if you are putting this
under your own configuration management; otherwise not the path to take,
because the installer generates real secrets where `.env.dist` hands you the
public ones from this repository and trusts you to remember to replace them.

```bash
curl -fsSLO https://raw.githubusercontent.com/vadymkykalo/railhook/main/docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/.env.dist -o .env

# Edit .env — at minimum, replace every one of these with a generated value:
#   WEBHOOK_ENCRYPTION_KEY, WEBHOOK_ENCRYPTION_SALT, JWT_SECRET
#   POSTGRES_PASSWORD and DB_PASSWORD (they must match)
#   REDIS_PASSWORD
# and set COMPOSE_PROFILES=embedded-db unless you are bringing your own database.

COMPOSE_PROFILES=embedded-db docker compose up -d

# Verify. Only the web port is published; the actuator is on 8082 inside the
# network and nginx proxies the health paths from it (see the port table above).
curl http://localhost/actuator/health/liveness
curl http://localhost/                     # dashboard
curl -o /dev/null -w '%{http_code}\n' http://localhost/api/v1/projects   # expect 401, not a connection error
```

### 4.3 Build from source (if you have cloned the repo anyway)

`docker-compose.yml` resolves every service to a published image.
`docker-compose.build.yml` is a small overlay that builds the three services
this project owns from the working tree instead:

```bash
git clone https://github.com/vadymkykalo/railhook.git
cd railhook
make up          # the overlay, the embedded-db profile and the topic creation
make health
```

By hand, if you would rather see it:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml \
  --profile embedded-db up -d --build
```

There used to be three Compose files describing the same seven services. They
drifted — the worker's actuator bind address ended up different in two of them,
which silently broke metrics scraping in one deployment path and not the other
— so they are now one file plus a 25-line build overlay.

**Or via Makefile (from a clone):**
```bash
make up-pull           # Pull pre-built images, no source build
make up-prod           # Build+run with production overrides, embedded DB
make up-prod-external  # External managed services (provide connection strings in .env)
```

### 4.4 Kubernetes + Helm (Medium / Large / Production)

The chart is published as an OCI artifact to GHCR on every release, so
`helm install` works against a clean cluster with **no repo clone**:

```bash
kubectl create namespace railhook

kubectl -n railhook create secret generic railhook-secrets \
  --from-literal=encryption-key=$(openssl rand -hex 16) \
  --from-literal=jwt-secret=$(openssl rand -base64 48)
kubectl -n railhook create secret generic railhook-postgresql-secret --from-literal=password=$DB_PASSWORD
kubectl -n railhook create secret generic railhook-redis-secret --from-literal=password=$REDIS_PASSWORD

helm install railhook oci://ghcr.io/vadymkykalo/charts/railhook --version <version> -n railhook \
  --set postgresql.external.host=your-postgres.example.com \
  --set kafka.external.bootstrapServers=kafka-1:9092,kafka-2:9092,kafka-3:9092 \
  --set redis.external.host=your-redis.example.com \
  --set ui.ingress.hosts[0].host=railhook.yourdomain.com

kubectl -n railhook get pods    # no ImagePullBackOff
```

If you need to customize the chart itself (not just `--set` overrides), clone
the repo and install the local copy instead — this also picks up
`values-production.yaml`:

```bash
cd deploy/helm

# Create namespace
kubectl create namespace railhook

# Create required secrets
kubectl -n railhook create secret generic railhook-secrets \
  --from-literal=encryption-key=$(openssl rand -hex 16) \
  --from-literal=jwt-secret=$(openssl rand -base64 48)

kubectl -n railhook create secret generic railhook-postgresql-secret \
  --from-literal=password=$DB_PASSWORD

kubectl -n railhook create secret generic railhook-redis-secret \
  --from-literal=password=$REDIS_PASSWORD

# Install with external services (recommended for production)
helm install railhook ./railhook -n railhook \
  --set postgresql.external.host=your-postgres.example.com \
  --set postgresql.external.database=railhook \
  --set postgresql.external.username=webhook_user \
  --set kafka.external.bootstrapServers=kafka-1:9092,kafka-2:9092,kafka-3:9092 \
  --set redis.external.host=your-redis.example.com \
  --set ui.ingress.hosts[0].host=railhook.yourdomain.com

# Verify
kubectl -n railhook get pods
kubectl -n railhook logs -l app.kubernetes.io/component=api --tail=20
```

### Production Values Override

Create `values-mycompany.yaml`:

```yaml
api:
  replicaCount: 3
  resources:
    requests:
      cpu: 500m
      memory: 1Gi
    limits:
      cpu: 2000m
      memory: 2Gi
  hpa:
    enabled: true
    minReplicas: 3
    maxReplicas: 15
  pdb:
    enabled: true
    minAvailable: 2

worker:
  replicaCount: 3
  env:
    KAFKA_DELIVERY_CONCURRENCY: "16"
    DB_POOL_MAX_SIZE: "40"
  hpa:
    enabled: true
    minReplicas: 3
    maxReplicas: 30
  pdb:
    enabled: true
    minAvailable: 2

kafka:
  topicPartitions: 24
  topicReplicationFactor: 3

backup:
  enabled: true
  schedule: "0 2 * * *"
  retainCount: 14
  storageSize: 50Gi

networkPolicy:
  enabled: true

postgresql:
  external:
    host: railhook-db.abc123.us-east-1.rds.amazonaws.com
    port: 5432
    database: railhook
    username: railhook_app
```

```bash
helm install railhook ./railhook -n railhook -f values-mycompany.yaml
```

---

## 5. Configuration Reference

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `WEBHOOK_ENCRYPTION_KEY` | AES-256 key for encrypting secrets (32 hex chars) | `a1b2c3d4e5f6...` |
| `WEBHOOK_ENCRYPTION_SALT` | Salt for key derivation (32 hex chars) | `f6e5d4c3b2a1...` |
| `JWT_SECRET` | JWT signing secret (min 32 chars) | `super-secret-jwt-key-...` |
| `DB_HOST` | PostgreSQL host | `postgres.example.com` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `railhook` |
| `DB_USERNAME` | Database user | `webhook_user` |
| `DB_PASSWORD` | Database password | (from secret) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers | `kafka-1:9092,kafka-2:9092` |
| `REDIS_HOST` | Redis host | `redis.example.com` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | (from secret) |

### Optional Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBHOOK_ALLOW_PRIVATE_IPS` | `false` (prod) | Allow delivery to private IPs |
| `WEBHOOK_ALLOWED_HOSTS` | (empty) | Comma-separated internal hosts allowlist |
| `WEBHOOK_TRUSTED_PROXIES` | (empty — trust nothing) | Comma-separated trusted reverse-proxy IPs/CIDRs; gates `X-Forwarded-For` (see §2) |
| `DB_POOL_MAX_SIZE` (API) / `WORKER_DB_POOL_MAX_SIZE` (Worker) | `20` (API), `40` (Worker) | HikariCP max connections. Distinct names on purpose — a single shared `DB_POOL_MAX_SIZE` in Docker Compose used to silently give the worker the API's smaller pool |
| `KAFKA_DELIVERY_CONCURRENCY` | `8` | Worker Kafka consumer threads |
| `SWAGGER_ENABLED` | `false` (prod) | Enable Swagger UI |
| `BILLING_ENABLED` | `false` | Enable billing/quotas (SaaS mode) |
| `BILLING_DEFAULT_PROVIDER` | `noop` | `stripe`, `wayforpay`, or `noop` |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN`, `ERROR` |

---

## 6. Upgrade Strategy

### Rolling Update (zero-downtime)

Railhook is designed for rolling updates:

1. **Database migrations run first** via init container (Flyway with built-in locking — only one pod runs migration)
2. **API pods update** with rolling strategy (new pods start before old ones terminate)
3. **Worker pods update** last (in-flight deliveries complete before pod terminates)

```bash
# Update image tags
helm upgrade railhook ./railhook -n railhook \
  --set api.image.tag=v1.5.0 \
  --set worker.image.tag=v1.5.0 \
  --set ui.image.tag=v1.5.0

# Monitor rollout
kubectl -n railhook rollout status deployment railhook-api
kubectl -n railhook rollout status deployment railhook-worker
```

### Rollback

```bash
# Rollback to previous revision
helm rollback railhook -n railhook

# Or to specific revision
helm history railhook -n railhook
helm rollback railhook 3 -n railhook
```

### Migration Compatibility

- **Flyway migrations are forward-only** — no down migrations
- **Kafka message format is backward-compatible** — old workers can consume new messages
- **API responses are additive** — new fields don't break old clients

---

## 7. Backup & Restore

### Automated Backups (Helm)

Enable the backup CronJob in your values:

```yaml
backup:
  enabled: true
  schedule: "0 2 * * *"  # Daily at 2 AM UTC
  retainCount: 14         # Keep 2 weeks of backups
  storageSize: 50Gi
```

### Manual Backup

```bash
# PostgreSQL custom format (compressed, supports selective restore)
pg_dump -h $DB_HOST -U $DB_USERNAME -d $DB_NAME -Fc -f railhook-$(date +%Y%m%d).dump
```

### Restore

```bash
# Stop services first
kubectl -n railhook scale deployment railhook-api railhook-worker --replicas=0

# Restore
pg_restore -h $DB_HOST -U $DB_USERNAME -d $DB_NAME \
  --clean --if-exists --no-owner railhook-20240115.dump

# Restart services
kubectl -n railhook scale deployment railhook-api --replicas=3
kubectl -n railhook scale deployment railhook-worker --replicas=3
```

### What to Backup

| Data | Method | Frequency | Critical? |
|------|--------|-----------|-----------|
| PostgreSQL | `pg_dump` or WAL archiving | Daily + continuous WAL | **Yes** |
| Encryption key + salt | Copy to offline vault | On creation/rotation | **Critical** |
| JWT secret | Copy to offline vault | On creation/rotation | **Critical** |
| Kafka topics | Recreatable from Helm job | N/A | No |
| Redis data | Not needed (ephemeral) | N/A | No |
| Helm values | Store in Git | Every change | Yes |

---

## 8. TLS & mTLS

### Ingress TLS (cert-manager + Let's Encrypt)

The Helm chart supports cert-manager out of the box:

```yaml
ui:
  ingress:
    annotations:
      cert-manager.io/cluster-issuer: letsencrypt-prod
    tls:
      - secretName: railhook-tls
        hosts:
          - railhook.yourdomain.com
```

### Database SSL

```yaml
api:
  env:
    DB_SSL_MODE: require  # or verify-full for strict verification
worker:
  env:
    DB_SSL_MODE: require
```

### mTLS for Webhook Delivery

Railhook supports per-endpoint mTLS for webhook delivery:

1. Upload client certificate via API:
   ```bash
   curl -X PUT /api/v1/projects/{projectId}/endpoints/{endpointId}/mtls \
     -F "certificate=@client.pem" \
     -F "privateKey=@client-key.pem"
   ```

2. Enable mTLS on the endpoint:
   ```bash
   curl -X PATCH /api/v1/projects/{projectId}/endpoints/{endpointId} \
     -d '{"mtlsEnabled": true}'
   ```

### Service-to-Service mTLS

For service mesh mTLS (Istio/Linkerd):
- Railhook services work with automatic sidecar injection
- No application-level changes needed
- Ensure mesh allows egress to external webhook endpoints

---

## 9. Monitoring

### Health Checks

```bash
# API health (actuator is split onto its own port, not the main 8080 — see §2 port table)
curl http://api:8082/actuator/health/liveness

# Worker health
curl http://worker:8081/actuator/health

# Detailed health (includes DB, Kafka, Redis status)
curl http://api:8082/actuator/health | jq .
```

### Monitoring Stack (Docker Compose — Self-Hosted)

A full Prometheus + Grafana stack ships in the `monitoring/` directory, **decoupled** from the main platform:

```bash
# Start the platform first
make up

# Start monitoring (Prometheus + Grafana)
make monitoring-up

# Open Grafana
#   http://localhost:3001
#   Login: railhook / railhook_monitor_2024
```

Four pre-built dashboards are auto-provisioned on first boot:

| Dashboard | What It Shows |
|-----------|---------------|
| **Railhook — Overview** | API health, request rate, 5xx rate, p95 latency, delivery pipeline, queue depth, DLQ, table sizes, billing reconciliation, circuit breaker |
| **Railhook — Worker & CB** | Circuit breaker trips/rejects/slow-trips, retry governor pending/batch/failures, queue depths, async thread pool |
| **Railhook — JVM & Micrometer** | Heap memory, GC pauses, threads, HTTP status codes & latency percentiles, HikariCP pool, CPU, file descriptors |
| **Railhook — Kafka** | Consumer lag by topic/partition, records consumed rate, fetch latency, producer send rate, queue time |

```bash
# Stop monitoring
make monitoring-down

# Override Grafana password
GF_ADMIN_PASSWORD=my_secret make monitoring-up
```

### Monitoring (Kubernetes — Production)

For Kubernetes with `kube-prometheus-stack`, enable in your Helm values:

```yaml
monitoring:
  serviceMonitor:
    enabled: true
    labels:
      release: prometheus   # must match your Prometheus Operator selector
  prometheusRule:
    enabled: true
    labels:
      release: prometheus
  grafanaDashboards:
    enabled: true
    labels:
      grafana_dashboard: "1"
```

This deploys:
- **ServiceMonitor** for API (`:8080`) and Worker (`:8081`) — auto-discovered by Prometheus Operator
- **PrometheusRule** with all alert rules (backlog, DLQ, circuit breaker, retry governor, API errors, staleness)
- **ConfigMap** with all 4 Grafana dashboard JSONs (auto-imported via Grafana sidecar)

### Prometheus Metrics

Both API and Worker expose Prometheus metrics:

```
API:    http://api:8080/actuator/prometheus
Worker: http://worker:8081/actuator/prometheus
```

### Key Metrics to Monitor

| Metric | Component | Alert Threshold |
|--------|-----------|-----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | API | > 0.5% of total |
| `hikaricp_connections_pending` | API/Worker | > 0 for 1 min |
| `kafka_consumer_fetch_manager_records_lag_max` | Worker | > 1000 for 5 min |
| `delivery_queue_depth{status="pending"}` | Worker | > 5000 for 10 min |
| `delivery_oldest_pending_age_seconds` | Worker | > 3600s |
| `circuit_breaker_state_transitions_total` | Worker | > 1/s for 5 min |
| `retry_governor_consecutive_failures` | Worker | > 5 |
| `billing_reconciliation_errors_total` | API | any increase |
| `delivery_attempts_table_rows` | API | > 10M (cleanup lag) |

## 10. Troubleshooting

### Common Issues

| Issue | Likely Cause | Quick Fix |
|-------|-------------|-----------|
| API returns 503 | DB connection pool exhausted | Increase `DB_POOL_MAX_SIZE`, check slow queries |
| Deliveries not being sent | Worker pods down or Kafka lag | Check worker pods, scale up |
| High DLQ rate | Endpoint permanently broken | Disable endpoint, check URL |
| Slow ingestion | DB write latency | Check PG `pg_stat_activity`, vacuum |
| Billing not working | `BILLING_ENABLED=false` | Set to `true` and configure provider |
| Login fails after upgrade | JWT secret changed | Expected — users must re-login |

### Logs

```bash
# API logs
kubectl -n railhook logs -l app.kubernetes.io/component=api -f --tail=100

# Worker logs (delivery processing)
kubectl -n railhook logs -l app.kubernetes.io/component=worker -f --tail=100

# Filter for errors only
kubectl -n railhook logs -l app.kubernetes.io/component=api | grep -i error
```

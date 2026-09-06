# Railhook Helm Chart

Self-hosted webhook infrastructure platform for Kubernetes.

## Prerequisites

- Kubernetes 1.24+
- Helm 3.8+
- PostgreSQL 16+, Kafka 3.7+, and Redis 7+ - **you must supply all three**

  This chart does not bundle PostgreSQL/Kafka/Redis subcharts. It used to
  depend on Bitnami's, but Bitnami moved its free chart/image catalog to a
  restricted "Legacy" tier in August 2025 (dropping Kafka from the catalog
  entirely) and is retiring its AWS-hosted mirror of the same content in
  June 2026 - repinning to a newer Bitnami version would just be betting on
  a catalog that keeps shrinking. Point `postgresql.external`,
  `kafka.external`, and `redis.external` in `values.yaml` at your own
  instances: managed services (RDS/Cloud SQL, MSK/Confluent Cloud,
  ElastiCache/Memorystore, etc.) for production, or your own
  StatefulSets/containers for a self-managed cluster - `postgres:16-alpine`,
  `apache/kafka:3.7.0`, and `redis:7-alpine` are the exact images this
  project's `docker-compose.yml` and CI test against, so they're a
  reasonable starting point if you're standing up your own.

## Quick Start

### 1. Create secrets

```bash
# Generate random secrets
ENCRYPTION_KEY=$(openssl rand -base64 32)
ENCRYPTION_SALT=$(openssl rand -base64 24)
JWT_SECRET=$(openssl rand -base64 64)
DB_PASSWORD=$(openssl rand -base64 32)
REDIS_PASSWORD=$(openssl rand -base64 32)

# Create Kubernetes secrets
kubectl create secret generic railhook-secrets \
  --from-literal=encryption-key="$ENCRYPTION_KEY" \
  --from-literal=encryption-salt="$ENCRYPTION_SALT" \
  --from-literal=jwt-secret="$JWT_SECRET"

kubectl create secret generic railhook-postgresql-secret \
  --from-literal=password="$DB_PASSWORD"

kubectl create secret generic railhook-redis-secret \
  --from-literal=password="$REDIS_PASSWORD"
```

All three keys in `railhook-secrets` are required: `encryption-key` and
`encryption-salt` together derive the key every endpoint secret in the database
is encrypted with, and both apps declare them with no default — a release
missing either gives you CrashLooping api and worker pods, not a degraded mode.

**Back these up, and never rotate `encryption-salt` in place.** It is an input
to the key derivation, so changing it makes every secret already stored decrypt
to garbage; a database backup restored without the same salt is unreadable.
`secrets.encryptionKey` / `secrets.encryptionSalt` / `secrets.jwtSecret` in
`values.yaml` point at the Secret name and key if you keep them somewhere else.

### 2. Configure external services

Edit `values.yaml` to point to your external PostgreSQL, Kafka, and Redis (all three are required - see Prerequisites):

```yaml
postgresql:
  enabled: false
  external:
    host: "postgres.example.com"
    port: 5432
    database: railhook
    username: webhook_user
    existingSecret: railhook-postgresql-secret

kafka:
  enabled: false
  external:
    bootstrapServers: "kafka-1:9092,kafka-2:9092,kafka-3:9092"

redis:
  enabled: false
  external:
    host: "redis.example.com"
    port: 6379
    existingSecret: railhook-redis-secret
```

### 3. Set the URL people will type

Verification, password-reset and invite links are built from `app.baseUrl`, and
`app.corsAllowedOrigins` (which defaults to it) is what the browser is allowed
to call the API from. Leave both empty and the chart derives them from the first
`ui.ingress` host — `https://…` when TLS is configured — so an install that sets
its ingress host needs nothing further:

```yaml
app:
  baseUrl: "https://app.railhook.yourdomain.com"
```

Set it explicitly when browsers reach Railhook by some other name than the
ingress host. With no ingress and no `app.baseUrl` the fallback is the in-cluster
UI Service, which is not an address anyone can follow from a mailbox.

`api.env.APP_ENV` is `production` by default, and `ProductionSafetyValidator`
then *refuses to start* if CORS still names localhost — so this is a startup
requirement, not a cosmetic one.

### 4. Configure email (optional, but on in production)

With email off, accounts are created already verified and nobody can be invited
to an organization. `values-production.yaml` turns it on, which means it needs a
relay:

```yaml
email:
  enabled: true
  from: noreply@railhook.yourdomain.com
  smtp:
    host: smtp.example.com
    port: 587
    username: railhook@example.com
    auth: true
    starttls: true
    existingSecret: railhook-smtp-secret      # key: smtp-password
```

```bash
kubectl create secret generic railhook-smtp-secret \
  --from-literal=smtp-password="$SMTP_PASSWORD"
```

`email.enabled: true` with an empty `smtp.host` is worse than leaving mail off:
signup accepts the account and then cannot deliver the verification link.

### 5. Install chart

```bash
# Both dev and production need external.* set for postgresql/kafka/redis -
# see "Configure external services" above and Prerequisites for why there's
# no embedded/subchart option.
helm install railhook ./railhook \
  --set postgresql.external.host=postgres.local \
  --set kafka.external.bootstrapServers=kafka.local:9092 \
  --set redis.external.host=redis.local

# Production
helm install railhook ./railhook -f values-production.yaml \
  --set postgresql.external.host=postgres.prod.local \
  --set kafka.external.bootstrapServers=kafka.prod.local:9092 \
  --set redis.external.host=redis.prod.local \
  --set ui.ingress.hosts[0].host=app.railhook.yourdomain.com
```

### 6. Access the UI

```bash
# Port-forward (development)
kubectl port-forward svc/railhook-ui 8080:80

# Production (via ingress)
# Visit https://app.railhook.yourdomain.com
```

## Configuration

See `values.yaml` for all configuration options.

### Common overrides

```yaml
# Scale API and Worker
api:
  replicaCount: 3
worker:
  replicaCount: 5

# Resource limits
api:
  resources:
    requests:
      cpu: 500m
      memory: 1Gi
    limits:
      cpu: 2000m
      memory: 2Gi

# Custom domain
ui:
  ingress:
    hosts:
      - host: webhooks.company.com
        paths:
          - path: /
            pathType: Prefix
```

## Production Deployment

See `values-production.yaml` for production-ready defaults:

- 3+ replicas for API and Worker
- HPA enabled with conservative targets
- PodDisruptionBudgets for HA (`api.pdb` / `worker.pdb` / `ui.pdb`; `minAvailable`
  by default, `maxUnavailable` if you set it instead)
- Network policies enabled
- Pod anti-affinity for zone distribution
- Transactional email on — which needs an SMTP relay, see step 4 above

```bash
helm install railhook ./railhook -f values-production.yaml
```

## Database Migrations

Flyway migrations run inside the API pod at startup, before it reports ready.
Replicas starting together serialise on a PostgreSQL advisory lock — Flyway's
own — so exactly one of them applies a given migration and the rest wait for it.
That is the same mechanism `docker-compose.yml` relies on.

This used to be described as an init container, and there was one; it could
never have worked. It ran the whole API jar with a `run-migration-only` flag no
code reads, without the encryption and JWT secrets the app requires to start,
and with nothing to make the JVM exit once Flyway had finished. See the comment
in `templates/api-deployment.yaml`. A separate migration step needs a jar entry
point that migrates and exits; there isn't one yet.

## Automated Backups

Enable the PostgreSQL backup CronJob:

```yaml
backup:
  enabled: true
  schedule: "0 2 * * *"  # Daily at 2 AM UTC
  retainCount: 14          # Keep 2 weeks
  storageSize: 50Gi
```

Backups are stored as `pg_dump` custom format files on a PVC. Old backups are automatically pruned.

## Kafka Topic Configuration

```yaml
kafka:
  topicPartitions: 12        # Partitions per topic (default: 12)
  topicReplicationFactor: 3  # Set to 3 for production multi-broker clusters
```

Topics are created automatically via a post-install/post-upgrade Helm hook job.

## Upgrading

```bash
# Pull new images
helm upgrade railhook ./railhook

# Zero-downtime rollout
# Flyway migrations run in init container before API starts
# Worker HPA scales based on Kafka consumer lag
```

## Monitoring

API and Worker serve Actuator on a **management port of their own** — 8082 for
the API, 8081 for the worker, the same split `docker-compose.yml` makes — not on
the port that serves traffic:

- `/actuator/health/liveness` - Liveness probe
- `/actuator/health/readiness` - Readiness probe
- `/actuator/prometheus` - Prometheus metrics

Both Services expose that port as `management`, and `monitoring.serviceMonitor`
scrapes it by that name. The split is what makes metrics reachable at all: on the
main port `/actuator/prometheus` goes through the authenticated filter chain and
answers 401, so every scrape failed and the shipped alert rules fired on absent
data. Nothing publishes the management port outside the cluster.

There is nothing further to switch on for `networkPolicy.enabled` installs: the
policies admit the management port from any namespace, since Prometheus normally
runs in its own.

## Uninstall

```bash
helm uninstall railhook

# Clean up persistent volumes (WARNING: data loss)
kubectl delete pvc -l app.kubernetes.io/instance=railhook
```

## Further Reading

- **[Self-Hosted Guide](../../../docs/SELF_HOSTED_GUIDE.md)** — hardware sizing, pre-flight checks, TLS, monitoring
- **[Operations Guide](../../../docs/OPERATIONS.md)** — quick start, scaling, common issues

## Support

- Documentation: https://github.com/vadymkykalo/railhook/tree/main/docs
- Issues: https://github.com/vadymkykalo/railhook/issues

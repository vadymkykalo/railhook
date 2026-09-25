# Railhook Helm chart

Needs Kubernetes 1.24+, Helm 3.8+, and your own PostgreSQL 16+, Kafka 3.7+ and Redis 7+. The chart
bundles none of them. CI tests against `postgres:16-alpine`, `apache/kafka:3.7.0` and `redis:7-alpine`.

## 1. Create secrets

```bash
kubectl create secret generic railhook-secrets \
  --from-literal=encryption-key="$(openssl rand -base64 32)" \
  --from-literal=encryption-salt="$(openssl rand -base64 24)" \
  --from-literal=jwt-secret="$(openssl rand -base64 64)"
kubectl create secret generic railhook-postgresql-secret \
  --from-literal=password="$(openssl rand -base64 32)"
kubectl create secret generic railhook-redis-secret \
  --from-literal=password="$(openssl rand -base64 32)"
```

All three keys in `railhook-secrets` are required; without them api and worker crash-loop.
Back up `encryption-key` and `encryption-salt`, and never change the salt in place: every stored
secret becomes unreadable.

## 2. Point at your services

```yaml
postgresql:
  external:
    host: "postgres.example.com"
    port: 5432
    database: railhook
    username: webhook_user
    existingSecret: railhook-postgresql-secret
kafka:
  external:
    bootstrapServers: "kafka-1:9092,kafka-2:9092"
redis:
  external:
    host: "redis.example.com"
    port: 6379
    existingSecret: railhook-redis-secret
app:
  baseUrl: "https://hooks.example.com"   # defaults to the first ui.ingress host
```

`APP_ENV` is `production` by default, and the API refuses to start if CORS
(`app.corsAllowedOrigins`, defaults to `app.baseUrl`) still names localhost.

Email is on in `values-production.yaml` and needs an SMTP relay:

```yaml
email:
  enabled: true
  from: noreply@example.com
  smtp:
    host: smtp.example.com
    port: 587
    username: railhook@example.com
    existingSecret: railhook-smtp-secret   # key: smtp-password
```

## 3. Install

```bash
helm install railhook ./railhook -f values-production.yaml \
  --set postgresql.external.host=postgres.prod.local \
  --set kafka.external.bootstrapServers=kafka.prod.local:9092 \
  --set redis.external.host=redis.prod.local \
  --set ui.ingress.hosts[0].host=hooks.example.com
kubectl port-forward svc/railhook-ui 8080:80   # without an ingress
```

`values-production.yaml` sets 3+ replicas, HPA, PodDisruptionBudgets, network policies and zone
anti-affinity. `values.yaml` has every option.

## Values that matter

| Value | What it does |
|---|---|
| `api.replicaCount`, `worker.replicaCount` | Replicas |
| `kafka.topicPartitions`, `kafka.topicReplicationFactor` | Topics, created by a post-install/upgrade hook job (12 partitions by default) |
| `backup.enabled`, `backup.schedule`, `backup.retainCount` | Nightly `pg_dump` to a PVC, old dumps pruned |
| `monitoring.serviceMonitor` | Scrapes the `management` port (api 8082, worker 8081) |
| `api.pdb`, `worker.pdb`, `ui.pdb` | PodDisruptionBudgets |

Flyway migrations run in the API pod at startup. Replicas starting together wait on Flyway's
advisory lock.

## Upgrade and uninstall

```bash
helm upgrade railhook ./railhook   # back up the database first: helm rollback does not undo migrations
helm uninstall railhook
kubectl delete pvc -l app.kubernetes.io/instance=railhook   # deletes the data
```

More: [Kubernetes docs](https://railhook.io/docs/self-hosting/kubernetes/),
[Operations](../../../docs/OPERATIONS.md).

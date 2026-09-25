# Railhook Operations

## Quick Start

```bash
curl -fsSL https://railhook.io/install.sh | bash

# Health, through nginx on the one published port. The actuator (8082) is not bound to the host.
curl -f http://localhost/actuator/health/liveness
```

From the install directory: `./railhook status | logs | stop | start | upgrade | backup | doctor`.
`doctor` re-runs the machine and configuration checks.

From a clone (`make up`, see the [README](../README.md)): `make health`, `make logs`,
`make logs-api`, `make logs-worker`.

## Production Deployment (Kubernetes)

```bash
kubectl create secret generic railhook-secrets \
  --from-literal=encryption-key="$(openssl rand -base64 32)" \
  --from-literal=jwt-secret="$(openssl rand -base64 64)"
kubectl create secret generic railhook-postgresql-secret \
  --from-literal=password="$(openssl rand -base64 32)"
kubectl create secret generic railhook-redis-secret \
  --from-literal=password="$(openssl rand -base64 32)"

helm install railhook oci://ghcr.io/vadymkykalo/charts/railhook --version <version> \
  --set postgresql.external.host=your-postgres-host \
  --set kafka.external.bootstrapServers=your-kafka:9092 \
  --set ui.ingress.hosts[0].host=app.yourdomain.com

# From a clone: helm install railhook ./deploy/helm/railhook \
#   -f ./deploy/helm/railhook/values-production.yaml --set ...
```

A post-install hook creates the topics: `deliveries.dispatch`,
`deliveries.retry.{1m,5m,15m,1h,6h,24h}`, `deliveries.dlq`.

### Retry ladder vs. DLQ hard-cap

Outgoing deliveries retry through six tiers (1m, 5m, 15m, 1h, 6h, 24h), up to 7 attempts: about
55h expected, 83h worst case with jitter. Incoming forwards stop after 5 attempts over 5 tiers
(up to 6h). The two ladders differ on purpose and live in `RetryLadderDefaults`, not in env vars.

`StaleDeliveryEscalationService` moves any `PENDING` delivery older than
`DELIVERY_ESCALATION_HARD_CAP_HOURS` (default 96) to the DLQ. The worker refuses to start if a
ladder's worst case does not fit inside that cap. If that check fails, raise the cap.

Per-subscription and per-destination ladders are set through the API. A malformed `retryDelays`
gets a `400`. A stored one that does not parse fails the delivery with `INVALID_RETRY_LADDER`.

## Known limitations

- **A Postgres restore needs Redis flushed and about an hour to settle.** See
  [Disaster recovery](#disaster-recovery).
- **Delivery is at-least-once.** Receivers must dedupe on the `webhook-id` header, which stays the
  same across retries.

## Registration on a public instance

- `EMAIL_ENABLED=true` makes email verification real. Without it every account is marked verified.
  Unverified accounts can read but not write.
- `CAPTCHA_SECRET_KEY` enables the signup challenge (Cloudflare Turnstile by default; for hCaptcha
  also set `CAPTCHA_VERIFY_URL`). The UI needs `CAPTCHA_SITE_KEY` (and `CAPTCHA_SCRIPT_URL` for
  hCaptcha); apply with `docker compose up -d ui`, no rebuild.
- Verification fails closed: if the provider is down, registration is refused. To stay open during
  an outage, turn the CAPTCHA off.
- With `APP_ENV=production` and `BILLING_ENABLED=true` the API will not start without both.
  Self-hosting needs neither.

## Changing settings on a Compose install

By hand: edit `.env` in the install directory, then `./railhook start`, which recreates the
containers whose configuration changed. `./railhook restart` does not re-read `.env`.

For an automated deploy, `./railhook upgrade` reads `NAME=value` lines from stdin and applies them
to `.env` first. Existing names are replaced, new ones appended, and only names are printed.
railhook.io is configured this way, from `DOTENV_<NAME>` variables and secrets in the GitHub
environment `production`. `./railhook settings < file` does the same step on its own.

Refused, nothing written: the encryption key and salt, `JWT_SECRET`, the Postgres and Redis
passwords, and the image tags (use the version argument of `upgrade`).

## The operator back-office

`/api/v1/admin/**` accepts only the `X-Platform-Admin-Token` header. Set `PLATFORM_ADMIN_TOKEN`;
empty (the default) keeps these endpoints unreachable.

```bash
export RAILHOOK_ADMIN_TOKEN=...        # or --token; never saved to the config file

railhook admin orgs                    # list organizations
railhook admin orgs --search acme
railhook admin orgs --suspended
railhook admin org $ORG_ID             # plan, counts, usage against limits
railhook admin suspend $ORG_ID --reason "Confirmed spam reports" --by ops@example.com
railhook admin reinstate $ORG_ID
```

Over HTTP:

```bash
curl -H "X-Platform-Admin-Token: $TOKEN" \
  'http://localhost/api/v1/admin/organizations?search=acme&size=20'
curl -H "X-Platform-Admin-Token: $TOKEN" http://localhost/api/v1/admin/organizations/$ORG_ID
curl -H "X-Platform-Admin-Token: $TOKEN" http://localhost/api/v1/admin/organizations/$ORG_ID/usage

# The reason is required, and the tenant sees it.
curl -X POST -H "X-Platform-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Confirmed spam reports","suspendedBy":"ops@example.com"}' \
  http://localhost/api/v1/admin/organizations/$ORG_ID/suspend
curl -X POST -H "X-Platform-Admin-Token: $TOKEN" \
  http://localhost/api/v1/admin/organizations/$ORG_ID/reinstate
```

There is no back-office page in the dashboard on purpose: it shares an origin with the API, so any
XSS would expose a token that works for every tenant.

The usage view shows the same counts and limits the customer sees on their billing page, and no
customer data (emails, URLs, payloads).

A suspended organization can read but not write; every write, ingest included, gets a 403 with the
operator's reason. Suspension is stored in `organizations.suspended_at`, separate from
`billing_status`, so a successful charge does not lift it. The decision is cached for
`ORGANIZATION_SUSPENSION_CACHE_TTL_SECONDS` (60), so other nodes pick it up within that window.
Both actions are audited as `ORGANIZATION_SUSPENDED` / `ORGANIZATION_REINSTATED`.

## Common Issues

### High Kafka lag
- Scale workers: `make scale-worker N=5` or `kubectl scale deployment railhook-worker --replicas=5`
- Check the DB connection pool in the logs
- Increase `KAFKA_DELIVERY_CONCURRENCY`

### Database issues
- Backup: `make backup-db` (Compose only)
- Check connections: `docker exec webhook-postgres pg_isready`
- Pool exhausted: raise `DB_POOL_MAX_SIZE` (API) or `WORKER_DB_POOL_MAX_SIZE` (worker)

### "Too many failed sign-in attempts": a locked account
An account locks after `AUTH_LOCKOUT_THRESHOLD` failed sign-ins (default 5). There is no admin
unlock. The user can:
- **Wait.** The lock starts at `AUTH_LOCKOUT_INITIAL_SECONDS` (60), doubles per failure, capped at
  `AUTH_LOCKOUT_MAX_SECONDS` (900).
- **Reset the password.** This clears the lock.

If password reset is impossible (for example mail is down):
```sql
UPDATE users SET failed_login_attempts = 0, lockout_expires_at = NULL, last_failed_login_at = NULL
 WHERE email = 'someone@example.com';
```

### Failed deliveries spike
- UI: Failed Messages. Bulk retry from there.
- Check endpoint availability.

### Failed forwards spike (incoming direction)
Alert on `incoming_forward_dlq_depth`.
- UI: Failed Forwards, or `GET /api/v1/projects/{projectId}/incoming-dlq`.
- Retry from there. A retry re-forwards only to the destination that failed. Do not use Time
  Machine replay for this: it sends to every enabled destination again.
- Check that the destination is up and still enabled. A disabled destination fails its forwards
  without retrying.

## Monitoring

Health:
- API: `http://localhost:8082/actuator/health/liveness`. Prefer `/liveness`: the aggregate
  `/actuator/health` reads DOWN when no SMTP server is reachable, even with `EMAIL_ENABLED=false`.
- Worker: `http://localhost:8081/actuator/health` (internal)

Metrics: `/actuator/prometheus` on the management port, 8082 for the API and 8081 for the worker.
It needs no auth there; the main port's `/actuator/**` does. See `monitoring/README.md`.

Alerting: the monitoring stack (a second Compose project, `docker compose -p
railhook-monitoring -f monitoring/docker-compose.yml` on an install.sh host, `make
monitoring-up` in a clone; needs `GRAFANA_ADMIN_PASSWORD` in `.env`) runs
Alertmanager, which routes `monitoring/prometheus/alerts.yml` (the platform) and
`host-alerts.yml` (disk, memory, containers, uptime, TLS, backups, error logs) to
email/Slack/webhook/Telegram via the `ALERTMANAGER_*` env vars (`.env.dist`). Only
Grafana is published, on loopback. See `monitoring/README.md`.

On railhook.io the deploy key runs `deploy/prod/railhook-deploy`, installed as
`/usr/local/bin/railhook-deploy`. After `./railhook upgrade` it downloads `monitoring/` for the
same tag and recreates the monitoring stack, so alert rules and dashboards follow each release.
After changing the script, copy it to the host:
`scp deploy/prod/railhook-deploy root@<host>:/usr/local/bin/railhook-deploy`.

**Kubernetes:** the chart sets `MANAGEMENT_PORT` (8082 API, 8081 worker) and exposes it as a named
`management` port on the container and Service. The `ServiceMonitor`, the API probes and the
NetworkPolicy use it. `MANAGEMENT_ADDRESS` is `0.0.0.0` so the worker's probes work. CI's
`helm-kind-smoke` job checks both management ports return 200 and the API traffic port does not.

## Backup & Restore

`backup-db` and `restore-db` work against the embedded Compose DB or an external Postgres
(`DB_MODE=external`, run in a throwaway `postgres:16-alpine` container). Backups are
`pg_dump -Fc` `.dump` files. Old `.sql.gz` backups still restore.

```bash
make backup-db
make backup-db DB_MODE=external DB_HOST=my-managed-pg.example.com DB_USER=... DB_PASSWORD=...

# Prompts for confirmation; CONFIRM=YES skips it.
make restore-db FILE=backups/webhook_platform_20260101_120000.dump
```

**Scheduled backups (Compose):** `make up` with the embedded DB starts a `db-backup` sidecar
running `deploy/scripts/db-backup.sh` every `DB_BACKUP_INTERVAL_SECONDS` (default 86400), keeping
`BACKUP_RETENTION_DAYS` (default 30). Check it with `docker compose logs db-backup`. A failed run
logs and retries next interval. Kubernetes uses `templates/db-backup-cronjob.yaml`.

**Restore drill (CI):** the `restore-drill` job in `.github/workflows/ci.yml` backs up, drops the
table, restores and checks the data.

### Disaster recovery

**Data loss** is at most one backup interval. **Recovery time** is the `pg_restore` time plus a
restart; measure it on your data.

**Restoring onto a new host** needs three things:

1. `.env`. `WEBHOOK_ENCRYPTION_KEY` and `WEBHOOK_ENCRYPTION_SALT` encrypt every endpoint secret in
   the dump. Without them the restored data is unreadable. Back `.env` up separately from the dump.
2. `docker-compose.yml`, or re-run `install.sh` at the same version.
3. The dump.

Then: `install.sh` (or `./railhook start`), stop the stack, `make restore-db FILE=...`, start it.

**After a restore:**

- **Redis: flush it** before starting the worker. Everything in it (rate limits, circuit breakers,
  sequence counters, quota counters) is derived and rebuilds from Postgres.
- **Kafka: leave it alone.** Messages for rolled-back deliveries are declined at the claim step
  (`"delivery already claimed or not PENDING"`); a burst of these in the worker log is expected.
  Do not reset consumer offsets.
- **Postgres: nothing by hand.** `StuckDeliveryRecoveryService` re-queues stranded `PENDING`
  deliveries after `stuck-delivery.stranded-pending-threshold-minutes` (60). Watch
  `delivery_oldest_pending_age_seconds` come down over about an hour.

Events accepted after the dump are lost. If you know the window, tell the affected customers.

## Scaling

```bash
# Docker Compose. The API publishes no host port; nginx balances across replicas via Compose DNS.
make scale-worker N=5
make scale-api N=3

# Kubernetes (HPA also scales)
kubectl scale deployment railhook-worker --replicas=10
```

## Upgrades

```bash
# Compose, installed with install.sh (backs up first, refuses to continue if the backup fails)
./railhook upgrade v2.13.0
./railhook upgrade             # at the tags already in .env

# Compose, from a clone
docker compose pull
make rebuild

# Kubernetes
helm upgrade railhook ./deploy/helm/railhook

# Rollback: images only
kubectl rollout undo deployment railhook-api
```

**Rollback does not undo the schema.** Migrations are forward-only. Rolling images back is fine
when the new release only added columns. If a migration dropped or retyped something the old code
reads, restore the backup instead.

**The API migrates, then the worker starts.** Only the API runs Flyway. `./railhook upgrade`
replaces the worker after the new API is serving. Elsewhere the worker waits until
`flyway_schema_history` reaches its bundled migration (`MigratedSchemaGate`), logging
`Waiting for the API to migrate the schema` every 30s, and exits after 15 minutes. If it exits,
read the API log: the API is a different release or its migration failed.

**Upgrade drill (CI):** the `upgrade-smoke` job installs the last release, creates data and an API
key, upgrades to the branch images, and checks the data and the old key still work. It does not
test a rolling upgrade where both versions run at once.

### Index builds block writes, on the tables where that matters

Twenty-four shipped migrations build an index with plain `CREATE INDEX` on a table that grows
without bound (`events`, `deliveries`, `delivery_attempts`, `incoming_events`,
`incoming_forward_attempts`, `outbox_messages`, `tunnel_request_log`, `audit_log`,
`usage_daily`). That blocks writes to the table until the build ends. On a large installation the
upgrade looks hung and ingest stops. They cannot be edited now (Flyway checksums).

**Upgrading a large installation across one of them needs a maintenance window.** Check what is
already applied:

```sql
SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

Anything newer is about to run. `MigrationIndexLockingTest` blocks new ones: indexes on these
tables must use `CONCURRENTLY` with `-- flyway:executeInTransaction=false`.

### V056: the tenant column is not an instant migration

`V056__tenant_organization_id.sql` adds `organization_id` to 31 tables, backfills it and sets
`NOT NULL`. The backfill and the `NOT NULL` scan take time, including across every partition of
`delivery_attempts` and `tunnel_request_log`. Run it in a window.

`SET NOT NULL` fails on orphan rows. Check first:

```sql
SELECT count(*) FROM deliveries d LEFT JOIN endpoints e ON e.id = d.endpoint_id
WHERE e.id IS NULL;
```

### Open Session In View is off

`spring.jpa.open-in-view: false`. A handler that returns a lazy association outside a transaction
fails with `LazyInitializationException: could not initialize proxy - no session` (a 500).

## Security Checklist

Production must have:
- [ ] `WEBHOOK_ENCRYPTION_KEY`: unique 32-char random key
- [ ] `JWT_SECRET`: unique 64-char random key
- [ ] `DB_PASSWORD`: strong, not default
- [ ] `REDIS_PASSWORD`: strong, not default
- [ ] `WEBHOOK_ALLOW_PRIVATE_IPS=false`
- [ ] `SWAGGER_ENABLED=false`
- [ ] `DB_SSL_MODE=require`
- [ ] TLS termination at ingress/load balancer
- [ ] `AUTH_BCRYPT_STRENGTH` at 12 unless login is measurably slow
- [ ] `AUTH_LOCKOUT_ENABLED=true` unless something in front already limits attempts per account

## Environment Variables

- `APP_ENV=production`: production mode
- `LOG_LEVEL=WARN`: less log output
- `DB_POOL_MAX_SIZE=20` (API); `WORKER_DB_POOL_MAX_SIZE=40` (worker)
- `KAFKA_DELIVERY_CONCURRENCY=8`: parallel deliveries per worker

All variables: `.env.dist`.

## More

- [Self-hosting](https://railhook.io/docs/self-hosting/overview/)
- [Architecture](./ARCHITECTURE.md)
- [Observability](https://railhook.io/docs/platform/observability/)
- [Organizations and roles](https://railhook.io/docs/platform/organizations-rbac/)
- [Data retention and export](https://railhook.io/docs/resources/data-retention/)
- [Static egress IP](https://railhook.io/docs/resources/static-egress-ip/)
- Issues: https://github.com/vadymkykalo/railhook/issues

# Hookflow Operations Guide

## Quick Start

```bash
curl -fsSL https://raw.githubusercontent.com/vadymkykalo/webhook-platform/main/install.sh | bash

# Health, on the one published port. The actuator itself is on 8082 inside the
# network and is not bound to the host; nginx proxies these two paths from it
# and 404s the rest.
curl -f http://localhost/actuator/health/liveness
```

Day-to-day, from the install directory: `./hookflow status | logs | stop |
start | upgrade | backup | doctor`. `doctor` re-runs the machine and
configuration checks against what is on disk.

Building from source instead (`git clone ... && make up`) is documented in the
[README](../README.md#building-from-source-contributors); `make health`,
`make logs`, `make logs-api`, `make logs-worker` work against that path.

## Production Deployment (Kubernetes)

```bash
# Create secrets
kubectl create secret generic hookflow-secrets \
  --from-literal=encryption-key="$(openssl rand -base64 32)" \
  --from-literal=jwt-secret="$(openssl rand -base64 64)"

kubectl create secret generic hookflow-postgresql-secret \
  --from-literal=password="$(openssl rand -base64 32)"

kubectl create secret generic hookflow-redis-secret \
  --from-literal=password="$(openssl rand -base64 32)"

# Install the published chart directly — no repo clone required:
helm install hookflow oci://ghcr.io/vadymkykalo/charts/hookflow --version <version> \
  --set postgresql.external.host=your-postgres-host \
  --set kafka.external.bootstrapServers=your-kafka:9092 \
  --set ui.ingress.hosts[0].host=app.yourdomain.com

# Or, from a clone, with the local chart + production values file:
# helm install hookflow ./deploy/helm/hookflow -f ./deploy/helm/hookflow/values-production.yaml \
#   --set postgresql.external.host=your-postgres-host \
#   --set kafka.external.bootstrapServers=your-kafka:9092 \
#   --set ui.ingress.hosts[0].host=app.yourdomain.com

# Topics are auto-created via post-install hook:
# deliveries.dispatch, deliveries.retry.{1m,5m,15m,1h,6h,24h}, deliveries.dlq
```

### Retry ladder vs. DLQ hard-cap

Outgoing deliveries retry through the 6 tiers above (1m, 5m, 15m, 1h, 6h, 24h) over up to 7
attempts — an expected span of ~55h and a worst case of ~83h once full jitter (0.5x-1.5x per
tier) is factored in. Independently, `StaleDeliveryEscalationService` force-escalates *any*
`PENDING` delivery older than `DELIVERY_ESCALATION_HARD_CAP_HOURS` (default **96h**) straight to
DLQ, regardless of how many attempts it has left — it's a safety net against unbounded backlog
growth, not part of the retry ladder itself. The default hard-cap is set above the ladder's
worst case on purpose, so a delivery genuinely gets to run through all 6 tiers before the safety
net kicks in.

The worker fails to start if either default ladder's worst case no longer fits inside the cap
(`RetryLadder.requireFitsWithin`, called from `RetrySchedulerService` for both directions), so
they cannot silently drift apart. **The ladder itself is not an environment variable.** The two
defaults are declared in `RetryLadderDefaults` and mirrored by the Flyway column defaults, and
they differ by direction on purpose — incoming forwards give up after 5 attempts over 5 tiers
(6h) rather than 7 over 6 (24h), because relaying somebody else's webhook is a different promise
from delivering the customer's own event. `DELIVERY_ESCALATION_HARD_CAP_HOURS` is the knob to
move if the startup check fails.

Per-subscription and per-destination ladders are set through the API and stored on the row. A
malformed `retryDelays` is rejected with a `400` at write time; a stored one that somehow does
not parse fails its delivery terminally with `INVALID_RETRY_LADDER` rather than being retried on
a substituted ladder.

## Known limitations

Recorded here rather than left to be discovered during an incident.

**A Postgres restore does not reconcile Kafka and Redis.** A point-in-time or full restore leaves
in-flight outbox rows, Kafka messages already published for events the restore rolled back, and
Redis counters that no longer agree with the restored database. There is no written procedure for
reconciling the three — see [Backup & Restore](#backup--restore) for the detail.

**Delivery is at-least-once.** An Attempt can succeed at the endpoint and fail to record. Receivers
must dedupe on the delivery id, which the `webhook-id` header carries unchanged across retries.

## Registration on a public instance

Two settings decide whether an open signup is a signup or a farm.

`EMAIL_ENABLED=true` makes verification real: without it, registration marks every account
verified on the spot, because a token nobody receives proves nothing about an address. The API
refuses every write from an unverified account and lets reads through, so the tenant can sign in
and be told to check their mail.

`CAPTCHA_SECRET_KEY` adds the challenge. The auth rate limit is per address, and an address is
the one thing a signup farm has plenty of. Cloudflare Turnstile by default; hCaptcha speaks the
same siteverify shape, so `CAPTCHA_VERIFY_URL` is all that changes. The dashboard needs
`VITE_CAPTCHA_SITE_KEY` at build time — without it the registration page renders no challenge
and sends no token, which is exactly what the unconfigured server side expects.

Verification **fails closed**: a provider that is unreachable or answering nonsense means
registration is refused, not waved through. A deployment that would rather stay open when the
provider is down should turn the CAPTCHA off — a decision someone makes, rather than an outage
making it for them.

With `APP_ENV=production` and `BILLING_ENABLED=true`, the API refuses to start without both.
Neither is required, or wanted, for self-hosting.

## The operator back-office

Everything under `/api/v1/admin/**` takes the `X-Platform-Admin-Token` header and nothing else —
no tenant JWT or API key satisfies it, however privileged the role. Set `PLATFORM_ADMIN_TOKEN`;
leaving it empty keeps these endpoints unreachable, which is the shipped default.

The CLI is the intended client:

```bash
export HOOKFLOW_ADMIN_TOKEN=...        # or pass --token; never saved to the config file

hookflow admin orgs                    # who is on this deployment
hookflow admin orgs --search acme
hookflow admin orgs --suspended        # only the ones currently stopped

hookflow admin org $ORG_ID             # plan, counts, and usage against the plan's limits

hookflow admin suspend $ORG_ID --reason "Confirmed spam reports" --by ops@example.com
hookflow admin reinstate $ORG_ID
```

The same over HTTP, for a script or a runbook that would rather not depend on the CLI:

```bash
curl -H "X-Platform-Admin-Token: $TOKEN" \
  'http://localhost/api/v1/admin/organizations?search=acme&size=20'

curl -H "X-Platform-Admin-Token: $TOKEN" \
  http://localhost/api/v1/admin/organizations/$ORG_ID
curl -H "X-Platform-Admin-Token: $TOKEN" \
  http://localhost/api/v1/admin/organizations/$ORG_ID/usage

# The reason is required, and the tenant is shown it.
curl -X POST -H "X-Platform-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Confirmed spam reports","suspendedBy":"ops@example.com"}' \
  http://localhost/api/v1/admin/organizations/$ORG_ID/suspend

curl -X POST -H "X-Platform-Admin-Token: $TOKEN" \
  http://localhost/api/v1/admin/organizations/$ORG_ID/reinstate
```

### Why there is no back-office page in the dashboard

There is deliberately no browser UI for any of this. The dashboard is served from the same
origin as the API, so a platform-admin token kept in a browser turns any XSS anywhere in the
tenant dashboard into the deployment's master credential — one that is the same secret for
every tenant on the instance and that no tenant role can otherwise reach. A terminal is a much
smaller blast radius than a page a hundred customers also load.

`hookflow admin` reads the token from `HOOKFLOW_ADMIN_TOKEN` or `--token` on each invocation
and never writes it to `~/.config/hookflow/config.json`, so it does not outlive the command and
`hookflow status` cannot print it.

### What the usage view answers

`hookflow admin org $ORG_ID` shows events this billing period, endpoints, projects and members,
each against the limit the tenant's plan allows — the **same** numbers the customer sees on
their own billing page, because it is the same service reading them under that tenant's scope
rather than a second set of queries. Two implementations of "how much have they used" is how a
support conversation ends up with the operator and the customer reading different figures.

It carries counts and limits only: no member emails, no endpoint URLs, no payloads. Answering
"who is this and are they in trouble" needs none of them, and a support view that shows customer
data by default becomes a reason not to give anyone the credential.

### What suspension does, and what it deliberately does not

A suspended organization can read and cannot write. Every mutating request is refused with 403
and the reason the operator typed, ingest included — which is the point, since ingest is what an
abusive tenant is doing. Reads stay open so the customer can sign in and be told what happened,
and so support can look at the same screens they can.

It is **not** `billing_status`. That column belongs to the payment state machine: the dunning
scheduler writes `SUSPENDED` there when a grace period expires, and the subscription lifecycle
overwrites it on the next sync — so an abuse suspension recorded there would be lifted by a
successful charge. Suspension lives in `organizations.suspended_at`, and the two are independent
on purpose. (Before this existed, `billing_status = SUSPENDED` was read by nothing at all: a
non-paying organization went on ingesting and delivering exactly as before.)

The decision is cached for `ORGANIZATION_SUSPENSION_CACHE_TTL_SECONDS` (60 by default), because
it is asked on the write path of every request. A suspend or reinstate takes effect immediately
on the node that served it and within that window on the others. Acceptable for an abuse
control; it would not be for an authorization one.

Both actions are written to the audit log as `ORGANIZATION_SUSPENDED` / `ORGANIZATION_REINSTATED`,
which is where a customer's "why did this stop working" gets answered.

## Common Issues

### High Kafka lag
- Scale workers: `make scale-worker N=5` or `kubectl scale deployment hookflow-worker --replicas=5`
- Check DB connection pool in logs
- Increase `KAFKA_DELIVERY_CONCURRENCY` env var

### Database issues
- Backup: `make backup-db` (docker-compose only)
- Check connections: `docker exec webhook-postgres pg_isready`
- Connection pool exhausted: increase `DB_POOL_MAX_SIZE` (API) or `WORKER_DB_POOL_MAX_SIZE` (Worker) — separately named on purpose, see `.env.dist`

### "Too many failed sign-in attempts" — a locked account
An account locks after `AUTH_LOCKOUT_THRESHOLD` consecutive failed sign-ins (default 5). There is
deliberately no administrator unlock, because one would make locking a known email address a
denial of service with no self-service way out. Two things end a lockout, both available to the
account holder:
- **Wait.** The window starts at `AUTH_LOCKOUT_INITIAL_SECONDS` (60), doubles per further failure
  and is capped at `AUTH_LOCKOUT_MAX_SECONDS` (900). It lapses on its own.
- **Reset the password.** Completing a reset clears the counter and the lockout immediately.

If an operator genuinely has to intervene (e.g. the mail transport is down and nobody can reset),
the state is three columns on `users` and clearing them is enough:
```sql
UPDATE users SET failed_login_attempts = 0, lockout_expires_at = NULL, last_failed_login_at = NULL
 WHERE email = 'someone@example.com';
```

### Failed deliveries spike
- Check DLQ: Navigate to UI → Failed Messages
- Bulk retry from UI
- Check endpoint availability

### Failed forwards spike (incoming direction)
`incoming_forward_dlq_depth` is the alertable gauge; it drops as the backlog is worked through.
- Navigate to UI → Failed Forwards, or `GET /api/v1/projects/{projectId}/incoming-dlq`
- Retry one or several from there. A retry re-forwards to the destination that failed and to
  nothing else, on a fresh retry ladder. Do **not** use the Time Machine's replay for this: it
  fans the incoming event out to *every* enabled destination, including the ones that already
  received it.
- Check destination availability, and that the destination is still enabled — a disabled
  destination fails its forwards terminally rather than retrying them.

## Monitoring

Health endpoints:
- API: `http://localhost:8082/actuator/health/liveness` (separate port from the main 8080 — see below; the aggregate `/actuator/health` also factors in the mail health indicator, which reads DOWN whenever no SMTP server is reachable even with `EMAIL_ENABLED=false`, so prefer `/liveness` for an up/down check)
- Worker: `http://localhost:8081/actuator/health` (internal)

Metrics (Prometheus): `/actuator/prometheus` — on port **8082** for the API,
**8081** for the worker (a separate `management.server.port` from the main app
port, so Prometheus can scrape without a JWT/API-key — the app's main-port
`/actuator/**` still requires one). See `monitoring/README.md` "Metrics-scrape
auth" for the full rationale. The Helm chart splits the port the same way, and
its `ServiceMonitor` scrapes the management port by name.

Alerting: `make monitoring-up` also starts Alertmanager (`:9093`), which routes
the 14 rules in `deploy/prometheus/alerts.yml` to Slack/webhook/email via the
`ALERTMANAGER_*` env vars (`.env.dist`). See `monitoring/README.md` "Alerting".

**Kubernetes (closed):** the chart sets `MANAGEMENT_PORT` on both deployments
(8082 for the API, 8081 for the worker), exposes it as a named `management`
port on the container and the Service, points the `ServiceMonitor` and the API's
probes at it, and opens it in the NetworkPolicy. Before that, every scrape in
Kubernetes returned 401 and the `PrometheusRule` alerts fired on no data — while
`helm-lint` stayed green, because nothing in CI ever applied the chart. The
`helm-kind-smoke` job does now, and asserts a 200 on both management ports and a
non-200 on the API's traffic port.

The worker was separately broken by `MANAGEMENT_ADDRESS` defaulting to
`127.0.0.1`, which left its actuator answering nothing outside the pod —
kubelet probes included. The chart sets it to `0.0.0.0`; the port is published
to no host.

## Backup & Restore

Both `backup-db` and `restore-db` work against the embedded Compose DB
(`docker exec`) or any external/managed Postgres (`DB_MODE=external`, via a
throwaway `postgres:16-alpine` container — no local `pg_dump`/`pg_restore`
needed). Backups are custom-format `.dump` files (`pg_dump -Fc`), restorable
with `pg_restore`; the older plain-SQL `.sql.gz` format is still readable
by `restore-db` for anyone restoring an older backup.

```bash
# Backup — embedded DB (default) or external:
make backup-db
make backup-db DB_MODE=external DB_HOST=my-managed-pg.example.com DB_USER=... DB_PASSWORD=...

# Restore (prompts for confirmation; CONFIRM=YES skips the prompt, e.g. in CI):
make restore-db FILE=backups/webhook_platform_20260101_120000.dump
```

**Scheduled backups (Compose):** starting the platform with `make up`
(embedded-DB profile) also starts a `db-backup` sidecar that runs
`deploy/scripts/db-backup.sh` on a fixed interval (`DB_BACKUP_INTERVAL_SECONDS`,
default 86400/daily) with age-based retention (`BACKUP_RETENTION_DAYS`, default
30) — mirroring `deploy/helm/hookflow/templates/db-backup-cronjob.yaml`, the
only prior scheduled backup (Kubernetes-only). `docker compose logs db-backup`
shows each run; a failed backup logs and retries on the next interval rather
than crash-looping the container.

**Restore drill (CI):** `.github/workflows/ci.yml`'s `restore-drill` job runs
backup → destroy the table → restore → assert the data is back, on every push/PR
that touches `deploy/scripts/**`, `docker-compose.yml`, or the Makefile's
database targets — see that job for the exact steps. An untested restore path
is the most common cause of an unusable backup; this is what turns "we take
backups" into a guarantee that they're restorable.

**Open question this repo doesn't fully answer yet — Postgres PITR vs.
Kafka/Redis state:** a point-in-time (or full) Postgres restore leaves
in-flight outbox rows, Kafka messages already published for events the restore
rolled back, and Redis counters that no longer agree with the restored DB.
There is no written procedure for reconciling the three.

## Scaling

```bash
# Docker Compose
make scale-worker N=5
make scale-api N=3     # The API publishes no host port, so replicas have
                       # nothing to fight over and this just works. nginx
                       # proxies to `api:8080` by Compose DNS, which
                       # load-balances across them on its own. To reach one
                       # specific replica: `docker compose exec api ...`.

# Kubernetes (auto-scales with HPA)
kubectl scale deployment hookflow-worker --replicas=10
```

## Upgrades

```bash
# Docker Compose
docker compose pull
make rebuild

# Kubernetes (zero-downtime)
helm upgrade hookflow ./deploy/helm/hookflow

# Rollback if needed
kubectl rollout undo deployment hookflow-api
```

**Upgrade drill (CI):** `.github/workflows/ci.yml`'s `upgrade-smoke` job installs the last
release tag, registers an account, creates a project and an API key, ingests an event, then
swaps in the images built from the branch and checks the rows survived and ingest still works —
using the credential minted before the upgrade, which is what a customer's integration does the
morning after. It is the only place Flyway meets a populated schema; a fresh install can never
exercise that, and it is where a migration written against an empty database goes wrong.

It does not prove a *rolling* upgrade. Both versions never run at once here, so a migration that
breaks the previous release's code while it is still serving — a `NOT NULL` column added without
a default, say — would pass this and fail in Kubernetes. See the V056 note below for what that
looks like in practice.

### V056 — the tenant column is not an instant migration

`V056__tenant_organization_id.sql` adds `organization_id` to 31 tables, backfills each one from
its parent, and sets `NOT NULL`. `ADD COLUMN` is O(1) in Postgres, but the backfill `UPDATE` and
the `SET NOT NULL` scan are not, and two of the tables — `delivery_attempts` and
`tunnel_request_log` — are partitioned, so the statement touches every partition. On an
installation with data, run the upgrade in a window rather than during peak ingest, and expect the
API pod's startup probe to wait on Flyway.

`SET NOT NULL` fails outright if any row could not be backfilled — an orphan whose parent row is
already gone. On a database that has been running a while, check before upgrading:

```sql
SELECT count(*) FROM deliveries d LEFT JOIN endpoints e ON e.id = d.endpoint_id
WHERE e.id IS NULL;
```

Nothing in this repository does a rolling upgrade across the column's introduction: it is
`NOT NULL` from the first release that has it, because there was no earlier release in production
writing rows without it.

### Open Session In View is off

`spring.jpa.open-in-view: false` since the same release, because OSIV opens a database session
before a request has established which organization it belongs to. A handler that
returns an entity with a lazy association now has to fetch it inside the transaction; the symptom
if one is missed is `LazyInitializationException: could not initialize proxy — no session` in the
API log, surfacing as a 500.

## Security Checklist

Production must have:
- [ ] `WEBHOOK_ENCRYPTION_KEY` - unique 32-char random key
- [ ] `JWT_SECRET` - unique 64-char random key  
- [ ] `DB_PASSWORD` - strong password, not default
- [ ] `REDIS_PASSWORD` - strong password, not default
- [ ] `WEBHOOK_ALLOW_PRIVATE_IPS=false`
- [ ] `SWAGGER_ENABLED=false`
- [ ] `DB_SSL_MODE=require`
- [ ] TLS termination at ingress/load balancer
- [ ] `AUTH_BCRYPT_STRENGTH` left at 12 (lower it only if a login is measurably slow on your hardware)
- [ ] `AUTH_LOCKOUT_ENABLED=true` unless something in front of the API already bounds attempts per account

## Environment Variables

Key settings:
- `APP_ENV=production` - enables production mode
- `LOG_LEVEL=WARN` - reduces log verbosity
- `DB_POOL_MAX_SIZE=20` (API); `WORKER_DB_POOL_MAX_SIZE=40` (Worker — a separately-named var on purpose, not a shared `DB_POOL_MAX_SIZE`)
- `KAFKA_DELIVERY_CONCURRENCY=8` - parallel deliveries per worker

## Detailed Documentation

- **[Self-Hosted Deployment Guide](./SELF_HOSTED_GUIDE.md)** — hardware sizing, pre-flight checks, Helm install, TLS, monitoring
- **[Architecture](./ARCHITECTURE.md)** — the two pipelines, the attempt lifecycle, consistency and failure modes
- **[Observability](./guides/observability.md)** — every metric worth alerting on, and the three to start with
- **[Access control and tenancy](./guides/rbac-and-tenancy.md)** — roles, scopes, and what `@TenantId` does and does not cover
- **[Data retention and export](./guides/data-retention.md)** — what is kept and for how long, and how to bound the two largest tables
- **[Static egress IP](./guides/static-egress-ip.md)** — giving customers a fixed address to allowlist

## Support

- Docs: https://github.com/vadymkykalo/webhook-platform
- Issues: https://github.com/vadymkykalo/webhook-platform/issues

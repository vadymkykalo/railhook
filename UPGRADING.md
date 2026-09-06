# Upgrading

## Unreleased

## v2.11.0

### The shipped defaults stopped naming a domain this project does not own

`hookflow.dev` serves an unrelated product, and it was the hardcoded value behind
`rel="canonical"`, `og:image`, `public/sitemap.xml`, `robots.txt`'s `Sitemap:` line, the
`sales@` / `support@` addresses on `/contact`, and the default `EMAIL_FROM` in `.env.dist`,
`docker-compose.yml`, `application.yml` and both Helm values files. Nothing replaces it with
another constant — a deployment now says what it is, or says nothing.

**Act on this if you never set `EMAIL_FROM`.** The default is now
`noreply@example.com`, which will not deliver. It never really did — mail from a domain you
do not own fails SPF and DKIM at the receiver — but the old value looked plausible enough to
leave alone. Set it to an address at a domain you control:

```env
EMAIL_FROM=noreply@your-domain.example
```

Two new build-time variables, both optional and both irrelevant to a private dashboard:

```env
# Public origin, if this deployment has one. Empty: the canonical follows the
# browser's own origin and index.html publishes no absolute self-reference.
VITE_SITE_URL=

# Domain behind the sales@ / support@ cards on /contact. Empty: those two cards
# are not rendered, which is the right answer for an internal deployment.
VITE_CONTACT_DOMAIN=
```

Being `VITE_*`, they are inlined at build time and take effect only when the UI image is
rebuilt; on the pre-built images they cannot be set at all.

If you serve a public site and want a sitemap that names it, regenerate the two files
together:

```bash
cd webhook-platform-ui && SITE_URL=https://your.domain npm run seo:sitemap
# then edit the Sitemap: line in public/robots.txt to match
```

## v2.10.0

*(These notes were previously filed under "Unreleased"; they describe upgrading to 2.10.0.)*

### `VITE_API_URL` now takes effect — check your `.env` before rebuilding the UI

`VITE_API_URL` and `VITE_CSP_EXTRA_CONNECT` were being passed as runtime
environment to the UI's nginx container, where they did nothing: Vite inlines
`import.meta.env.VITE_*` into the bundle at build time. They are build args now,
so they finally do what they always claimed to.

**This means a stale value starts biting.** Older `.env.dist` shipped
`VITE_API_URL=http://localhost:8080`. If your `.env` still carries that line and
you rebuild the UI image (`make up`, `make dev-ui`, `make rebuild-ui`), the
bundle will hardcode `http://localhost:8080` as the API origin — which breaks
the moment the dashboard is served from anywhere but your own machine.

Set it empty unless the API genuinely lives on another origin:

```env
VITE_API_URL=
```

Empty is the right answer for every compose deployment: the UI's nginx proxies
`/api/`, `/ws/tunnel`, `/hook/` and `/ingress/` to the api service, so the
browser talks to its own origin and no CORS is involved. If you do point it at a
separate origin, add that origin to `CORS_ALLOWED_ORIGINS` as well.

On the pre-built images neither variable can be
changed at all — there is no build to feed — so both are gone from that file.

### `JAVA_OPTS` in `.env` is now `API_JAVA_OPTS` / `WORKER_JAVA_OPTS`, and appends

Compose was setting the containers' `JAVA_OPTS` to the empty string by default,
which overrode the JVM tuning baked into the images. Every default deployment
was running at the stock `MaxRAMPercentage=25` — roughly 192 MB of heap inside a
768 MB limit instead of the intended ~576 MB. If you sized your containers
around observed memory use, expect the JVMs to now use the headroom you gave
them.

`API_JAVA_OPTS` / `WORKER_JAVA_OPTS` still work and are unchanged in spelling,
but they now **append** to the image tuning rather than replace it. An explicit
`-Xmx` still wins over `MaxRAMPercentage`, so existing values keep their
meaning. To replace the tuning wholesale, set the container's `JAVA_OPTS`
directly.

### `RETRY_LADDER_DEFAULT_*` removed — no action required

`RETRY_LADDER_DEFAULT_DELAYS_SECONDS` and `RETRY_LADDER_DEFAULT_MAX_ATTEMPTS` are
gone. If your `.env` still sets them they are ignored, and you can delete the lines.

They never set the default retry ladder, despite reading as though they did. The
actual defaults live in the Flyway column defaults for
`subscriptions.retry_delays` / `incoming_destinations.retry_delays` and in the api
services that create those rows; these two variables only fed the startup check
that the ladder fits inside `DELIVERY_ESCALATION_HARD_CAP_HOURS`. Setting them
therefore changed what that check compared against and nothing else — lowering one
made the check pass while live rows still carried the long ladder.

The check now validates the ladders the platform actually hands out (declared in
`RetryLadderDefaults`), for both directions. `DELIVERY_ESCALATION_HARD_CAP_HOURS`
is unchanged and still the knob to move if the check fails.

### Malformed retry ladders are now rejected at write time

`POST`/`PUT` on a subscription or an incoming destination returns `400` when
`retryDelays` is not a comma-separated list of positive whole seconds, or when
`maxAttempts` is outside 1–100. Previously such a value was accepted and then
silently replaced at delivery time by a hardcoded ladder in the worker.

Existing rows are not migrated and are not validated on read. Both columns have
`NOT NULL`/`DEFAULT` declarations and the api was the only writer, so a stored
malformed ladder can only have come from direct SQL. If you have written to these
columns by hand, check them before upgrading:

```sql
SELECT id, retry_delays FROM subscriptions
 WHERE retry_delays !~ '^[0-9]+(\s*,\s*[0-9]+)*$';
SELECT id, retry_delays FROM incoming_destinations
 WHERE retry_delays !~ '^[0-9]+(\s*,\s*[0-9]+)*$';
```

A row that matches will now fail its attempt instead of being delivered on a
substituted ladder.

## Upgrading from v1.x to v2.x

v2.0.0 is a breaking release. Read this whole section before upgrading a
running v1.x deployment — the default path (`git pull` + `docker compose up
-d` / `helm upgrade`) will start, but will silently lose access to existing
encrypted data and may become unreachable from outside `localhost`.

### 1. Existing encrypted secrets will not decrypt

Endpoint signing secrets, incoming-source secrets, and destination auth
credentials are AES-256-GCM encrypted at rest. The key derivation changed:

| | v1.x | v2.x |
|---|---|---|
| Algorithm | `SHA-256(masterKey)`, truncated to 16 bytes | `PBKDF2WithHmacSHA256`, 65,536 iterations, 256-bit output |
| Inputs | `WEBHOOK_ENCRYPTION_KEY` only | `WEBHOOK_ENCRYPTION_KEY` **and** `WEBHOOK_ENCRYPTION_SALT` |
| Effective key size | AES-128 | AES-256 |

(`webhook-platform-common/src/main/java/com/webhook/platform/common/util/CryptoUtils.java`,
`deriveKey`)

`EncryptionKeyRegistry` has no fallback to the old algorithm — it always
derives with PBKDF2. **Any secret encrypted before upgrading will fail to
decrypt after upgrading**, because the derived key is completely different,
not because of a missing salt value. There is no in-place migration for
this; you have two options:

- **Fresh deployment** (recommended if you don't have production traffic
  relying on existing secrets): stand up v2.x against a new database.
- **In-place upgrade**: after upgrading, every endpoint signing secret,
  incoming source secret, and destination auth credential must be manually
  re-entered through the dashboard/API. Deliveries using the old secrets
  will fail signature verification (outgoing) or be undeliverable
  (destinations needing auth) until re-entered. There is no bulk
  re-encryption tool.

Either way, set `WEBHOOK_ENCRYPTION_SALT` (16+ characters, unique per
deployment, see `.env.dist`) before starting v2.x — the app will derive a
wrong key silently if you reuse the v1.x `.env` without adding it.

### 2. Flyway schema history was reset, not extended

Between `v1.0.3` and `v2.0.0`, all pre-2.0 migrations (`V001`–`V025`) were
deleted and replaced with a new, consolidated set
(`V001__initial_schema.sql` … `V009__outbox_last_attempt_at.sql`). This is a
new baseline, not a continuation of the old numbering — a v1.x database
already has `V001`–`V025` recorded in `flyway_schema_history` with
checksums from the *old* files. Starting the v2.x API against that database
will fail Flyway validation (unknown/mismatched migrations).

This release is built for a **fresh database**. If you need to carry
forward existing data:

1. Take a full backup first.
2. Compare the old (`git show v1.0.3:webhook-platform-api/src/main/resources/db/migration/`)
   and new schemas by hand — table/column names changed in several places
   (e.g. `users`/`organizations`/`memberships` are now created directly in
   `V001` instead of across `V010`–`V012`).
3. Either `flyway baseline` the v2.x history against your already-migrated
   v1.x schema (only safe if you've manually verified the resulting schema
   matches `V001`–`V009` exactly) or write a one-off data migration into
   the new schema. There is no supported automated path — treat this as a
   manual, audited migration, not a `flyway migrate`.

### 3. Ports no longer bind to all interfaces by default

`docker-compose.yml` used to publish Kafka (`9092`/`9093`), Redis
(`6379`), and the API (`8080`) on `0.0.0.0`. In v2.x:

- Kafka and Redis are hardcoded to `127.0.0.1:<port>:<port>`.
- The API port now goes through a new `API_BIND` variable, **defaulting to
  `127.0.0.1`** (`.env.dist`).

If you expose the API directly (not through the `ui` service's nginx) —
for example a reverse proxy on the same host, or a deployment that skips
the bundled UI — set `API_BIND=0.0.0.0` explicitly in `.env`, or the API
becomes unreachable from outside the host after upgrading.

> `API_BIND` no longer exists. The api service publishes no host port at
> all in current releases, so there is nothing to bind: reach it through
> the dashboard's nginx on `HOOKFLOW_PORT`, or uncomment the example
> `ports:` block in `docker-compose.yml` to publish it yourself.

### 4. Redis requires a password

`docker-compose.yml`'s Redis service now runs with `--requirepass`, driven
by `REDIS_PASSWORD` (defaulted in `.env.dist`, but you should set your own
in production). If your v1.x `.env` doesn't define `REDIS_PASSWORD`, the
compose default (`webhook_redis_pass`) is used — change it before exposing
Redis beyond localhost.

### 5. `TEST_ENDPOINT_BASE_URL` default changed

`docker-compose.yml` no longer sets `container_name` on the `api`/`worker`/
`ui` services, so the old hostname `webhook-api` no longer resolves inside
the Docker network. The default `TEST_ENDPOINT_BASE_URL` changed from
`http://webhook-api:8080` to `http://api:8080` (Compose's service-name
DNS) to match. If your `.env` hardcodes the old value, update it or
generated Test Endpoint URLs will point at a host that doesn't resolve.

### Not breaking (mentioned for completeness)

- The PHP SDK's vendored dependencies (`sdks/php/vendor/`) were removed
  from version control — run `composer install` in `sdks/php` if you build
  the PHP SDK from source. Published Packagist releases are unaffected.
- Request/payload size limits, auth rate limiting, mTLS support, the
  incoming-webhooks (ingress) pipeline, DLQ management, payload
  transformation, and i18n are new, additive functionality — no action
  needed on upgrade beyond the encryption/schema/networking items above.

## Upgrading between other versions

- **v2.0.0 → v2.1.0 → v2.2.0 → v2.2.1**: purely additive Flyway migrations
  (`V010`–`V042`); a normal `flyway migrate` (i.e. just starting the new
  version against the existing v2.x database) applies them in order with
  no manual steps.
- **v2.2.0** added optional multi-key encryption for zero-downtime key
  rotation (`WEBHOOK_ENCRYPTION_KEYS`, `WEBHOOK_ENCRYPTION_KEY_ACTIVE_VERSION`).
  Leaving these unset keeps using `WEBHOOK_ENCRYPTION_KEY` as before — no
  action required unless you want to adopt key rotation.
- **v1.0.0 → v1.1.0 → v1.0.1 → v1.0.2 → v1.0.3**: no schema or config
  changes requiring action; these were CI/SDK-publishing and stability
  fixes. See [CHANGELOG.md](CHANGELOG.md) for the tag-numbering anomaly in
  this range (the `1.0.x` patch tags were cut after `1.1.0`, not before
  it).

# Upgrading

Only the releases that need something from you are listed. Everything else upgrades with
`./railhook upgrade` (Compose) or `helm upgrade`.

## 2.31.0

- The `./railhook` helper no longer has a `monitoring` command, and `./railhook upgrade` no longer
  refreshes `monitoring/`. The running containers are untouched. Manage them from the install
  directory with plain Compose:

  ```bash
  docker compose -p railhook-monitoring --env-file .env -f monitoring/docker-compose.yml ps
  ```

  To update the stack, download `monitoring/` for the new release and run `up -d` the same way.

## v2.29.0

- An Endpoint or incoming Destination that accepted nothing for 72 hours (at least 10 failed
  attempts) is now disabled, and its queued deliveries go to Failed Messages. To keep the old
  behaviour, set `ENDPOINT_AUTO_DISABLE_ENABLED=false` for both api and worker.
- A receiver's `Retry-After` on 429 and 503 is now honoured, up to
  `WEBHOOK_RETRY_AFTER_MAX_SECONDS` (6h). Set it to `0` to ignore the header.

## v2.20.7

Email addresses become case-insensitive. The migration stops, and the API does not start, if two
accounts differ only by case or surrounding spaces. Check before upgrading and fix every row it
returns:

```sql
select lower(btrim(email)), count(*) from users group by 1 having count(*) > 1;
```

## v2.17.1

The UI reads the CAPTCHA settings and the site URL at startup. The build arguments are gone.

```bash
sed -i 's/^VITE_CAPTCHA_SITE_KEY=/CAPTCHA_SITE_KEY=/; s/^VITE_CAPTCHA_SCRIPT_URL=/CAPTCHA_SCRIPT_URL=/' .env
sed -i '/^VITE_SITE_URL=/d' .env   # the public origin now comes from APP_BASE_URL
```

If a `docker-compose.override.yml` points `ui` at an image you built, delete that `ui:` block.
On Helm, set `ui.captcha.siteKey` (and `ui.captcha.scriptUrl` for hCaptcha).

## v2.17.0

- `HOOKFLOW_BIND`, `HOOKFLOW_PORT` and `HOOKFLOW_DOMAIN` are no longer read. If your `.env` has
  only the old names, rename them:

  ```bash
  grep -E '^(HOOKFLOW|RAILHOOK)_(BIND|PORT|DOMAIN)=' .env
  sed -i 's/^HOOKFLOW_\(BIND\|PORT\|DOMAIN\)=/RAILHOOK_\1=/' .env   # only if no RAILHOOK_ line exists yet
  ```

- `VITE_CONTACT_DOMAIN` is now `RAILHOOK_CONTACT_DOMAIN`, read at runtime (Helm: `ui.contactDomain`):

  ```bash
  sed -i 's/^VITE_CONTACT_DOMAIN=/RAILHOOK_CONTACT_DOMAIN=/' .env
  docker compose up -d ui
  ```

- With `TUNNEL_INGRESS_BASE_URL` unset, incoming-source and tunnel URLs are built from
  `APP_BASE_URL` instead of `http://localhost:8080` (Helm: `app.ingressBaseUrl`).
- The installer refuses releases before 2.12.0.

## v2.16.0

- The API container has no fixed name. `docker logs webhook-api` no longer works; use
  `docker compose logs api`. Update scripts and alerts that name the container.
- An upgrade now replaces `docker-compose.yml` and keeps the old one as
  `docker-compose.yml.previous`. If you edited it, diff the two after upgrading.
- `API_REPLICAS=2` removes the short 502 window during an upgrade (about 600 MB more memory).

## v2.15.0

nginx no longer sends its own Content-Security-Policy. If you added origins to `nginx.conf`, move
them to `VITE_CSP_EXTRA_CONNECT`.

## v2.14.0

- With `APP_ENV=production`, a password reset link is no longer written to the log. If production
  runs without SMTP, set up SMTP first, or nobody can reset a password.
- `spring.kafka.producer.*` in `application.yml` was never read and is removed. Use
  `KAFKA_PRODUCER_DELIVERY_TIMEOUT_MS` and `KAFKA_PRODUCER_MAX_BLOCK_MS`.

## v2.12.0

Hookflow is renamed to Railhook. Old images stay pullable, so a running install keeps working
until you upgrade it.

| | Was | Now |
|---|---|---|
| npm | `@webhook-platform/node` | `@railhook/node` |
| PyPI | `webhook-platform` | `railhook` |
| Packagist | `webhook-platform/php` | `railhook/php` |
| Images | `ghcr.io/vadymkykalo/hookflow-{api,worker,ui}` | `ghcr.io/vadymkykalo/railhook-{api,worker,ui}` |
| Chart | `oci://ghcr.io/vadymkykalo/charts/hookflow` | `oci://ghcr.io/vadymkykalo/charts/railhook` |

- Python: `import hookflow` becomes `import railhook`. Node and PHP: `Hookflow` becomes
  `Railhook`, `HookflowError` becomes `RailhookError`, the PHP namespace `Hookflow\` becomes `Railhook\`.
- Kubernetes: this is not a `helm upgrade`. Install the new release, check it, then uninstall the
  old one. While both run they share the Kafka consumer group, which is safe.
- `HOOKFLOW_*` variables for the installer and CLI are now `RAILHOOK_*`, with no fallback.
- The CLI is `railhook`, and the deployment wrapper is `./railhook`. Reinstall the CLI and delete
  the old `./hookflow` wrapper.

## v2.11.0

The default `EMAIL_FROM` is now `noreply@example.com`, which will not deliver. Set it to an
address at a domain you own.

## v2.10.0

- `VITE_API_URL` is now a build argument. If your `.env` has `VITE_API_URL=http://localhost:8080`,
  set it empty before rebuilding the UI. Empty is right for every Compose install.
- `API_JAVA_OPTS` / `WORKER_JAVA_OPTS` now append to the image's JVM tuning instead of replacing
  it. Expect the JVMs to use more of their memory limit.
- `RETRY_LADDER_DEFAULT_DELAYS_SECONDS` and `RETRY_LADDER_DEFAULT_MAX_ATTEMPTS` are removed and
  ignored; delete them. They only changed a startup check, never the ladder.
  `DELIVERY_ESCALATION_HARD_CAP_HOURS` is still the setting to move if that check fails.
- A malformed retry ladder is rejected with `400`. If you wrote these columns by hand, check them:

  ```sql
  SELECT id, retry_delays FROM subscriptions WHERE retry_delays !~ '^[0-9]+(\s*,\s*[0-9]+)*$';
  SELECT id, retry_delays FROM incoming_destinations WHERE retry_delays !~ '^[0-9]+(\s*,\s*[0-9]+)*$';
  ```

## v2.2.0

Optional multi-key encryption (`WEBHOOK_ENCRYPTION_KEYS`, `WEBHOOK_ENCRYPTION_KEY_ACTIVE_VERSION`).
Unset, `WEBHOOK_ENCRYPTION_KEY` is used as before.

## v1.x to v2.x

v2.0.0 is built for a fresh database.

1. Secrets encrypted by 1.x do not decrypt: the key derivation changed to PBKDF2 and needs
   `WEBHOOK_ENCRYPTION_SALT`. Set the salt before starting 2.x, then either start on a new
   database or re-enter every endpoint secret, incoming-source secret and destination credential.
2. The Flyway history was reset to a new `V001`-`V009` baseline, so a 1.x database fails
   validation. Back up, then either baseline against a schema you checked by hand or migrate the
   data into a new database.
3. Kafka and Redis bind to `127.0.0.1`. The API publishes no host port; reach it through the UI's
   nginx on `RAILHOOK_PORT`, or add a `ports:` block yourself.
4. Redis requires `REDIS_PASSWORD`. Set your own.
5. `TEST_ENDPOINT_BASE_URL` defaults to `http://api:8080`. Replace `http://webhook-api:8080` if
   your `.env` has it.

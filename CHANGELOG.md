# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.7.0] - 2026-10-08

### Added

- Deliveries and Forwards in the DLQ (Failed Messages) are deleted after `DATA_RETENTION_DLQ_DAYS`, 14 by default. Until now only Event retention removed them, after 90 days outgoing and 30 incoming. `-1` keeps the old behaviour.
- `./railhook prune` removes the Railhook images of every version except the running one (and one more, if you name it) and all but the five newest `backup-*.dump` files. `./railhook upgrade` runs it at the end, keeping the version it upgraded from: each upgrade left about 1.5 GB of images and a dump behind.

### Changed

- Info alerts, such as a non-empty DLQ, are no longer mailed; they went out again every 12 hours for as long as they lasted. Slack, Telegram and the webhook still get them, and Grafana shows them.
- Promtail's memory limit is 160 MB instead of 96 MB, which it sat at 94% of, and cAdvisor's is 128 MB instead of 192 MB.

## [3.6.1] - 2026-10-01

### Fixed

- The dashboard sidebar stays in place while a dialog is open. On a scrolled page it moved up by the scroll distance, because the dialog's scroll lock turned the page body into its own scroller.

## [3.6.0] - 2026-10-01

### Added

- `DISCORD` alert channel: one embed per firing with the rule, the value against the threshold and a link to the project's incidents. The webhook URL is stored encrypted and never returned.
- `NO_TRAFFIC` alert condition: fires when the project receives no events for the rule's window, and resolves on the next event.
- Alert rules can be edited from the Alerts page. A stored secret is never shown; leaving it blank keeps it.
- `GET /api/v1/alert-channels` and `GET /api/v1/alert-conditions` list the installed channels and conditions with a JSON Schema of the settings each takes. The Alerts form is built from them, so adding a channel or condition is one class.

### Changed

- **Breaking API change:** an alert rule's channel settings move into `channelConfig`. `webhookUrl`, `emailRecipients`, `integrationKey` and `opsgenieRegion` are gone from the request and the response; send `channelConfig: {"url": …}`, `{"recipients": …}`, `{"routingKey": …}` or `{"apiKey": …, "region": …}` instead. The response has `configuredSecrets` in place of `integrationKeyConfigured`. `channel` and `alertType` are plain strings. Existing rules are migrated by `V004`.
- A `CONSECUTIVE_FAILURES` rule without an endpoint is refused when saved. It was accepted and never fired.
- A rule's threshold is required only by the conditions that read it.

## [3.5.3] - 2026-10-01

### Security

- The audit log no longer keeps credentials sent in a request. It stored each request body as sent, so an endpoint's signing secret, a PagerDuty routing key or an Opsgenie API key was kept there in plain text. Any field named like a secret, password, token or key is now written as `[redacted]`.

## [3.5.2] - 2026-10-01

### Removed

- The README screenshots. They showed the dashboard before 3.5.0.

## [3.5.1] - 2026-10-01

### Fixed

- Per-endpoint analytics count each delivery once. The query joined the endpoint's subscriptions and each delivery's attempts, so an endpoint with three subscriptions showed three times its deliveries, and a retried delivery counted once per attempt.

## [3.5.0] - 2026-10-01

### Changed

- The dashboard has a new layout. A dark sidebar holds the project switcher and each section's pages, with counts for Failed Messages, Failed Forwards, firing alerts and open incidents. The project overview leads with what needs attention. Deliveries opens a delivery's attempts beside the list. Endpoints shows each endpoint's delivered share and p95 over 24 hours and why a failing one is failing. Alerts is a table of rules with their current state. Every page has its own mobile layout. Workflows and the workflow builder are unchanged.

## [3.4.1] - 2026-10-01

### Fixed

- A `FAILURE_RATE` alert rule counts deliveries that went to the DLQ as failed. It counted only `FAILED`, so an endpoint that refused every request, with each delivery ending in the DLQ, never tripped it.

## [3.4.0] - 2026-09-30

### Added

- Analytics takes a custom `from`/`to` range of up to 90 days besides the 24h, 7d and 30d presets, and `GET /api/v1/dashboard/projects/{projectId}/analytics/export` returns the same numbers as CSV: one row per hour or day, then one row per endpoint. The analytics page has a range picker and an Export CSV button.
- `PAGERDUTY` and `OPSGENIE` alert channels. A rule triggers a PagerDuty event or creates an Opsgenie alert when it fires, and resolves or closes it when the condition clears. The routing key or API key is stored encrypted, never returned, and included in key rotation (`alertRulesRotated` in the rotation response).
- Workflows with the `SCHEDULE` trigger now run. `triggerConfig` takes a 5-field `cron` and an IANA `timezone` (default `UTC`), both checked on save. After downtime a missed schedule runs once, not once per missed tick, and a tick runs once even with several API instances. The workflow response has `nextRunAt`, a run has `scheduledFor`, and `GET /api/v1/projects/{projectId}/workflows/schedule-preview` returns the next three run times. The editor sets the schedule on the trigger node.

### Changed

- An unknown analytics `period` is refused with `400` instead of falling back to 24h.
- A `WARNING` alert rule opens an incident when it fires too, not only a `CRITICAL` one; `INFO` rules open none and cannot page. Further firings are added to the open incident's timeline, and the incident is resolved automatically when the evaluator sees the condition clear. The Incidents page shows the rule that opened an incident and whether it was resolved automatically.

### Fixed

- Analytics hourly and daily buckets are cut in UTC. They followed the database session's time zone while still being labelled UTC.

## [3.3.1] - 2026-09-30

### Added

- Release images are also published to Docker Hub as `railhook/railhook-api`, `railhook/railhook-worker` and `railhook/railhook-ui`. Set `DOCKER_REGISTRY=railhook/railhook` in `.env` to pull from there instead of GHCR.

## [3.3.0] - 2026-09-30

### Added

- `GET /api/v1/incoming-providers` lists the providers an incoming source can verify, with the header each signs in. The sources pages read their provider list from it.

### Changed

- An inbound provider is one class implementing `InboundProvider`. Adding one no longer touches the API schema, the UI or the SDKs; `CONTRIBUTING.md` has the steps.
- An incoming webhook is deduplicated by the event id of the provider its source names. A `GENERIC` source is keyed only by `X-Webhook-Id`; before, it also picked up Stripe, GitHub and other provider ids from whatever headers arrived.
- `providerType` is a string in the OpenAPI spec rather than an enum, and an unknown value is refused with `400`.
- The ingress handshake answer (Slack's `url_verification`) is documented as a plain JSON object; the `SlackUrlVerificationResponse` schema is gone.

### Removed

- The `ProviderType` type in the Node SDK and the `ProviderType` enum in the Python SDK. Both were already missing providers; `providerType` is a string.

## [3.2.1] - 2026-09-30

### Changed

- The README shows how to run Railhook locally in two commands and on a server in one.
- The production deploy waits for the API to report healthy before it checks pages, so a deploy onto an empty database no longer fails its checks while the schema is created.
- Dependency updates: UI patch group, Node and MCP SDK dev dependencies, `brace-expansion` 1.1.21, GitHub Actions.

## [3.2.0] - 2026-09-30

### Changed

- A claim takes from an endpoint or destination only its free concurrency (`WEBHOOK_MAX_CONCURRENT_PER_ENDPOINT` minus what is in flight), and the worker polls again at once after a partial batch. Before, one endpoint was held to about 25 deliveries per second and extra claims were deferred for up to a minute. `WEBHOOK_CLAIM_PER_TARGET` is removed.
- Every time column is `timestamptz`. 72 columns were `timestamp` without a zone, which read correctly only while the database session ran in UTC. Partition bounds and the daily statistics buckets are fixed to UTC explicitly.

## [3.1.0] - 2026-09-30

### Changed

- The 87 migrations are replaced by one `V001__schema.sql` that creates the current schema. It drops what only history needed: the outbox trigger function, the `_legacy` partitions, and four indexes other indexes already cover.

### Removed

- `DB_SSL_MODE` and `DB_JDBC_URL`. Nothing read either; the JDBC URL is built from `DB_HOST`, `DB_PORT` and `DB_NAME`.

## [3.0.0] - 2026-09-28

### Removed

- Kafka. The api writes a delivery or forward row and the worker claims due rows from PostgreSQL, so the stack is PostgreSQL, Redis, api, worker and ui. The outbox table, its publisher, the retry scheduler, the stuck and stranded sweeps and the DLQ topics are gone with it.

### Changed

- The worker claims at most a few rows per endpoint or destination per poll and moves through targets in turn, so one target's backlog no longer delays the others.
- A claim that is not finished within `WEBHOOK_CLAIM_TIMEOUT_SECONDS` is taken again under a new token; the lost holder can no longer write its outcome.
- Delivery and forward statuses change only along an explicit table of allowed transitions.

### Fixed

- Parking an ordered delivery behind its predecessor is fenced by its claim token.
- A forward whose event or destination disappeared is failed only while its claim still holds.
- `./railhook upgrade` removes containers of services a release dropped.

## [2.32.2] - 2026-09-26

### Fixed

- The container scan in CI fetches the Trivy databases from three registries with retries, so one registry outage no longer fails it.

## [2.32.1] - 2026-09-26

### Changed

- The site header links to the GitHub repository again.

## [2.32.0] - 2026-09-26

### Changed

- Logs no longer carry full email addresses or customer and alert URLs (masked, host only). Per-event, per-delivery and configuration-change lines moved from INFO to DEBUG; the audit log still records every configuration change.
- API errors that used to carry the generic `client_error` or `server_error` now carry a specific code (`invalid_request`, `captcha_failed`, `gone`, `account_locked`, `rate_limit_exceeded`, `service_unavailable`, `internal_error`, `authorization_pending`, or the existing `unauthorized`, `forbidden`, `not_found`, `conflict`); status and message are unchanged. The public contact, tester and demo forms' errors now include `status` like every other error. The errors page in the docs lists every code, and the OpenAPI spec documents every error response as `ErrorResponse`.

### Fixed

- `make up-pull` started the stack without its PostgreSQL container.
- `./railhook doctor` checks the installation against the installer of the release it runs, not the newest one; `./railhook help` lists `settings`.
- The installer's memory refusal says the real minimum (2 GiB) and both Compose-missing messages match.
- `.env.dist` sets `APP_BASE_URL` to the port it publishes on, so emailed links from a copied `.env` open the dashboard.
- Automatically opened status-page incidents no longer say someone is looking into it.
- The docs' installer options table names `--admin-email` and `--refresh` and describes `--yes` correctly; the operations guide's retry totals match the ladder.

## [2.31.1] - 2026-09-25

### Changed

- The About, Security and Contact pages are gone (301 to the home page and the docs); shorter Privacy, Terms and Pricing; plainer dashboard text.

## [2.31.0] - 2026-09-25

### Changed

- New look for the site, the dashboard and the docs: monochrome, one accent colour, in English and Ukrainian.
- The site's changelog page is gone; `/changelog` redirects to this file.
- `install.sh` is less than half as long, with the same flags and behaviour.
- The `./railhook` helper no longer manages monitoring; start it with `docker compose -p railhook-monitoring -f monitoring/docker-compose.yml up -d`.

## [2.30.2] - 2026-09-22

### Fixed

- `railhook listen` no longer fails with 500 on rare tunnel slugs, and unknown MCP methods return a JSON-RPC error.

## [2.30.1] - 2026-09-22

### Added

- SaaSHub badge in the site footer.

## [2.30.0] - 2026-09-21

### Added

- Transformations can be written in JavaScript, run in a sandbox with limits set by `TRANSFORM_SCRIPT_*`.
- `CANCELLED` delivery outcome for a script that cancels a delivery.

## [2.29.0] - 2026-09-20

### Added

- Incoming verification for Square, Adyen, SendGrid and HubSpot.
- Endpoints that only fail are disabled automatically, and owners get an email.
- `Retry-After` is honoured; retryable status codes are set per subscription.

## [2.28.2] - 2026-09-20

### Fixed

- The workflow builder works on phones.

## [2.28.1] - 2026-09-20

### Fixed

- FIFO ordering no longer breaks when a delivery is retried.

## [2.28.0] - 2026-09-20

### Changed

- API keys moved to Settings; the deliveries list shows the event type.

## [2.27.1] - 2026-09-20

### Fixed

- The docs keep the language switch on phones.

## [2.27.0] - 2026-09-20

### Fixed

- With `DEMO_ENABLED=false` the API deletes the demo organization and its data on start.

## [2.26.0] - 2026-09-19

### Added

- Read-only live demo at `/demo`, off unless `DEMO_ENABLED=true`.
- `BLOG_ENABLED` serves the blog; it is off by default.

## [2.25.0] - 2026-09-19

### Added

- Blog at `/blog` with an RSS feed.

## [2.24.0] - 2026-09-19

### Added

- Product screenshots on the landing page.

## [2.23.0] - 2026-09-18

### Added

- Optional onboarding emails (`ONBOARDING_EMAILS_ENABLED`).
- Browser-only signature verifier at `/tools/webhook-signature`.

## [2.22.0] - 2026-09-18

### Added

- Contact widget that sends to `EMAIL_SUPPORT_ADDRESS`.

## [2.21.2] - 2026-09-18

### Changed

- Footer links to the MCP server docs.

## [2.21.1] - 2026-09-18

### Changed

- Header links to Pricing.

## [2.21.0] - 2026-09-18

### Added

- MCP server at `/mcp` for AI agents, with OAuth sign-in for claude.ai and ChatGPT.
- Customer portal: embed a session so your users manage their own endpoints and retries.
- Public webhook tester at `/tester` (`PUBLIC_TESTER_ENABLED`).

## [2.20.13] - 2026-09-18

### Fixed

- Tunnels relay bodies byte for byte, so provider signatures verify behind `railhook tunnel`.

## [2.20.12] - 2026-09-18

### Fixed

- Inviting an unverified address is refused, and ordered events from `POST /events` are delivered in order.

## [2.20.11] - 2026-09-18

### Fixed

- Verifying an endpoint behind an offline tunnel returns `TUNNEL_OFFLINE`.

## [2.20.10] - 2026-09-18

### Fixed

- Test events reach pattern subscriptions.

## [2.20.9] - 2026-09-17

### Fixed

- An API key can only reach its own project; several other access and duplicate-delivery fixes.

## [2.20.8] - 2026-09-15

### Fixed

- `./railhook upgrade` replaces the worker only after the API has migrated.

## [2.20.7] - 2026-09-14

### Changed

- **Breaking:** email addresses become case-insensitive; the migration stops on duplicates. See [UPGRADING.md](UPGRADING.md).

## [2.20.6] - 2026-09-14

### Fixed

- Overview stays on the project you last opened.

## [2.20.5] - 2026-09-14

### Fixed

- A workflow can no longer write events into another organization's project.

## [2.20.4] - 2026-09-14

### Fixed

- A CLI tunnel survives an API restart and keeps its URL.

## [2.20.3] - 2026-09-14

### Fixed

- Rate limits count each client instead of each CDN edge, and Kafka topics survive a container recreate.

## [2.20.2] - 2026-09-14

### Fixed

- Kafka runs with a 512m heap instead of filling its memory limit.

## [2.20.1] - 2026-09-14

### Fixed

- Grafana dashboards and alerts show real data.

## [2.20.0] - 2026-09-13

### Added

- Platform admin panel at `/admin/platform` for `PLATFORM_ADMIN_EMAILS`.
- `./railhook monitoring up` starts Grafana, Prometheus, Loki and Alertmanager with email alerts.
- Users can change their sign-in email.

## [2.19.2] - 2026-09-13

### Fixed

- Test endpoint URLs use `APP_BASE_URL`, and the free plan has every feature.

## [2.19.1] - 2026-09-13

### Fixed

- Layouts for phones.

## [2.19.0] - 2026-09-13

### Added

- Sign in with Google when `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` are set.

## [2.18.1] - 2026-09-13

### Fixed

- Form fields no longer zoom on iPhone.

## [2.18.0] - 2026-09-13

### Fixed

- Unknown URLs return 404 instead of the landing page.

## [2.17.2] - 2026-09-13

### Added

- `./railhook settings < file` applies settings to `.env`.

## [2.17.1] - 2026-09-13

### Changed

- The UI image reads `APP_BASE_URL` and CAPTCHA settings at startup; the `VITE_*` build args are gone.

## [2.17.0] - 2026-09-13

### Added

- Docs site at `/docs/` with an API reference.
- One-line install: `curl -fsSL https://railhook.io/install.sh | bash`.
- `install.sh --domain <host> --behind-proxy` for an existing reverse proxy.

## [2.16.0] - 2026-09-12

### Changed

- `railhook upgrade` replaces the API with no downtime; use `docker compose logs api` instead of `docker logs webhook-api`.

## [2.15.0] - 2026-09-12

### Fixed

- Incoming webhooks are verified against the raw bytes received.
- The registration CAPTCHA can be turned on.

## [2.14.0] - 2026-09-12

### Fixed

- Sixteen reliability fixes, including three causes of duplicate deliveries and a stalled Kafka partition.

## [2.13.0] - 2026-09-07

### Added

- `railhook upgrade [version]` takes a backup first.
- Users can erase their own account (`DELETE /api/v1/auth/me`).
- Optional per-organization API rate limit.

## [2.12.0] - 2026-09-07

### Changed

- **Hookflow is now Railhook**: new package, image, chart and CLI names, and `HOOKFLOW_*` variables become `RAILHOOK_*`.

## [2.11.0] - 2026-09-06

### Changed

- Spring Boot 4.1.1.
- Email verification is enforced by the API, and registration can require a CAPTCHA.
- Organizations can be suspended by the platform operator.

## [2.10.0] - 2026-09-04

### Added

- Active sessions with per-session revoke, API key rotation with a grace window, and an organization switcher.
- Incoming DLQ with retry and purge, and Twilio verification.
- Alert rules are evaluated and fire.

## [2.9.1] - 2026-08-29

### Fixed

- The API reference lists paging as `page`, `size` and `sort`.

## [2.9.0] - 2026-08-29

### Fixed

- Incoming Forwards use the same claim fence as outgoing deliveries, preventing a duplicate forward.

## [2.8.0] - 2026-08-28

### Fixed

- The Node SDK is published from the release again.

## [2.7.0] - 2026-08-28

### Added

- [Standard Webhooks](https://www.standardwebhooks.com) signatures on every endpoint, and `verifyStandardWebhook` in all SDKs.

## [2.6.1] - 2026-08-28

### Fixed

- The UI image is published again, so `install.sh` works; several duplicate-delivery and session fixes.

## [2.6.0] - 2026-08-27

### Added

- `install.sh` installs with one command; `--domain` adds HTTPS.
- Only the UI port is published; nginx proxies the API.

## [2.5.0] - 2026-08-23

### Changed

- New dashboard design and an API reference generated from `openapi.yaml`.

## [2.4.0] - 2026-08-23

### Changed

- Stale incoming Forwards move to the DLQ after `FORWARD_ESCALATION_HARD_CAP_HOURS`.
- **If your `.env` sets `DELIVERY_ESCALATION_HARD_CAP_HOURS=48`, change it to 96**, or the worker will not start.

## [2.3.0] - 2026-08-22

### Changed

- Spring Boot 3.5; the Helm chart no longer bundles PostgreSQL, Redis and Kafka.

## [2.2.1] - 2026-03-18

### Fixed

- Small worker fix.

## [2.2.0] - 2026-03-16

### Added

- Rules engine, workflow engine, billing, and the `railhook` CLI with a local tunnel.
- Encryption key rotation (`WEBHOOK_ENCRYPTION_KEYS`).

## [2.1.0] - 2026-03-02

### Added

- Wildcard subscriptions, an event schema registry and replay.

## [2.0.0] - 2026-03-01

### Changed

- **Breaking:** new encryption key derivation (`WEBHOOK_ENCRYPTION_SALT` required) and a new migration baseline. See [UPGRADING.md](UPGRADING.md).
- Incoming webhooks, mTLS, endpoint verification and the PHP SDK.

## [1.1.0] - 2026-02-16

### Added

- DLQ management, payload transformation, custom headers and the PHP SDK.

## [1.0.1] - 2026-02-18

### Added

- First publish of the Node, Python and PHP SDKs.

## [1.0.2] - 2026-02-18

### Fixed

- PHP SDK CI configuration.

## [1.0.3] - 2026-02-18

### Fixed

- PHP SDK package metadata.

## [1.0.0] - 2025-12-17

### Added

- First release: event ingestion, subscriptions, signed delivery with retries and a DLQ, and a dashboard.

[Unreleased]: https://github.com/vadymkykalo/railhook/compare/v2.32.2...HEAD
[2.32.2]: https://github.com/vadymkykalo/railhook/compare/v2.32.1...v2.32.2
[2.32.1]: https://github.com/vadymkykalo/railhook/compare/v2.32.0...v2.32.1
[2.32.0]: https://github.com/vadymkykalo/railhook/compare/v2.31.1...v2.32.0
[2.20.2]: https://github.com/vadymkykalo/railhook/compare/v2.20.1...v2.20.2
[2.20.1]: https://github.com/vadymkykalo/railhook/compare/v2.20.0...v2.20.1
[2.20.0]: https://github.com/vadymkykalo/railhook/compare/v2.19.2...v2.20.0
[2.19.2]: https://github.com/vadymkykalo/railhook/compare/v2.19.1...v2.19.2
[2.19.1]: https://github.com/vadymkykalo/railhook/compare/v2.19.0...v2.19.1
[2.19.0]: https://github.com/vadymkykalo/railhook/compare/v2.18.1...v2.19.0
[2.18.1]: https://github.com/vadymkykalo/railhook/compare/v2.18.0...v2.18.1
[2.18.0]: https://github.com/vadymkykalo/railhook/compare/v2.17.2...v2.18.0
[2.17.2]: https://github.com/vadymkykalo/railhook/compare/v2.17.1...v2.17.2
[2.17.1]: https://github.com/vadymkykalo/railhook/compare/v2.17.0...v2.17.1
[2.17.0]: https://github.com/vadymkykalo/railhook/compare/v2.16.6...v2.17.0
[2.10.0]: https://github.com/vadymkykalo/railhook/compare/v2.9.1...v2.10.0
[2.9.1]: https://github.com/vadymkykalo/railhook/compare/v2.9.0...v2.9.1
[2.9.0]: https://github.com/vadymkykalo/railhook/compare/v2.8.0...v2.9.0
[2.8.0]: https://github.com/vadymkykalo/railhook/compare/v2.7.0...v2.8.0
[2.7.0]: https://github.com/vadymkykalo/railhook/compare/v2.6.1...v2.7.0
[2.6.1]: https://github.com/vadymkykalo/railhook/compare/v2.6.0...v2.6.1
[2.6.0]: https://github.com/vadymkykalo/railhook/compare/v2.5.0...v2.6.0
[2.5.0]: https://github.com/vadymkykalo/railhook/compare/v2.4.0...v2.5.0
[2.4.0]: https://github.com/vadymkykalo/railhook/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/vadymkykalo/railhook/compare/v2.2.1...v2.3.0
[2.2.1]: https://github.com/vadymkykalo/railhook/compare/v2.2.0...v2.2.1
[2.2.0]: https://github.com/vadymkykalo/railhook/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/vadymkykalo/railhook/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/vadymkykalo/railhook/compare/v1.0.3...v2.0.0
[1.1.0]: https://github.com/vadymkykalo/railhook/compare/v1.0.0...v1.1.0
[1.0.1]: https://github.com/vadymkykalo/railhook/compare/v1.1.0...v1.0.1
[1.0.2]: https://github.com/vadymkykalo/railhook/compare/v1.0.1...v1.0.2
[1.0.3]: https://github.com/vadymkykalo/railhook/compare/v1.0.2...v1.0.3
[1.0.0]: https://github.com/vadymkykalo/railhook/releases/tag/v1.0.0

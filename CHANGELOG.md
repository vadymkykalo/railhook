# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/vadymkykalo/railhook/compare/v2.31.1...HEAD
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

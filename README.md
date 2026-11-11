[![AgentHub 已收录：Railhook](https://myagenthub.cn/badge/io.github.vadymkykalo/railhook)](https://myagenthub.cn/p/io.github.vadymkykalo/railhook)

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/railhook-logo-dark.svg">
  <img src="docs/brand/railhook-logo.svg" alt="Railhook" height="48">
</picture>

**Open-source webhook gateway. Send webhooks to your customers and receive them from Stripe,
GitHub and others, with retries, signatures and a record of every attempt. Self-hosted or in
[Railhook Cloud](https://railhook.io/register).**

[![Latest release](https://img.shields.io/github/v/release/vadymkykalo/railhook?label=release)](https://github.com/vadymkykalo/railhook/releases/latest)
[![CI](https://github.com/vadymkykalo/railhook/actions/workflows/ci.yml/badge.svg)](https://github.com/vadymkykalo/railhook/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-000000.svg)](./LICENSE)
[![GHCR](https://img.shields.io/badge/GHCR-ghcr.io%2Fvadymkykalo%2Frailhook-000000?logo=docker&logoColor=white)](https://github.com/vadymkykalo?tab=packages&repo_name=railhook)
[![Docker pulls](https://img.shields.io/docker/pulls/railhook/railhook-api)](https://hub.docker.com/r/railhook/railhook-api)

<a href="https://www.saashub.com/railhook?utm_source=badge&utm_campaign=badge&utm_content=railhook&badge_variant=color&badge_kind=approved"><img src="https://cdn-b.saashub.com/img/badges/approved-color.png?v=1" alt="Railhook on SaaSHub" height="40"></a>

[Website](https://railhook.io) · [Docs](https://railhook.io/docs/) ·
[API reference](https://railhook.io/docs/api-reference/) · [Railhook Cloud](https://railhook.io/register) · [Changelog](./CHANGELOG.md)

</div>

## Install

Locally, with Docker:

```bash
git clone https://github.com/vadymkykalo/railhook && cd railhook
make up
```

Then open http://localhost:8080. `make up` writes a `.env` with development secrets, builds the
images and starts PostgreSQL, Redis, the API, the worker and the dashboard.

On a server with a domain (HTTPS through Let's Encrypt):

```bash
curl -fsSL https://railhook.io/install.sh | bash -s -- --domain hooks.example.com --email ops@example.com
```

It generates the secrets, pulls the latest release and adds `./railhook` for `status`, `logs`,
`upgrade` and `backup`. About 3 GiB of RAM. Plain Compose and Helm:
[Install with Docker](https://railhook.io/docs/self-hosting/install-docker/),
[Kubernetes](https://railhook.io/docs/self-hosting/kubernetes/).

## Send an event

Create a project, an endpoint and an API key in the dashboard, then:

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-API-Key: $RAILHOOK_API_KEY" \
  -H "Idempotency-Key: order-12345-completed" \
  -H "Content-Type: application/json" \
  -d '{"type":"order.completed","data":{"orderId":"ord_12345"}}'
```

Railhook signs it and delivers it to every endpoint subscribed to `order.completed`.

## What it does

- Sends each event to every endpoint subscribed to its type. The event is stored in the same
  transaction as the API call; the worker claims it from Postgres and delivers it.
- Retries a failed delivery after 1m, 5m, 15m, 1h, 6h and 24h (7 attempts). What still fails
  goes to Failed Messages for bulk retry.
- Signs every request with HMAC-SHA256 in [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks)
  headers. A rotated secret keeps signing alongside the new one for 24 hours.
- Deduplicates on `Idempotency-Key`: a repeated key returns the first event instead of a new one.
- Optional per-endpoint ordering, rate limits and a circuit breaker.
- Customer portal: embed a page where your customers add endpoints, pick event types, and see and
  retry their deliveries.
- Replay: resend one delivery, or replay a time range of events as new deliveries.
- Incoming webhooks: one URL per source, signature verified (Stripe, GitHub, GitLab, Shopify,
  Slack, Twilio, Square, Adyen, SendGrid, HubSpot, or generic HMAC), raw request stored, then
  forwarded to your destinations with retries after 1m, 5m, 15m and 1h (5 attempts).
  A missing provider is one class to add: see
  [Adding an inbound provider](CONTRIBUTING.md#adding-an-inbound-provider).
- Every attempt is recorded with its request, response and timing.
- SDKs for [Node](sdks/node), [Python](sdks/python) and [PHP](sdks/php), a [CLI](railhook-cli)
  that tunnels webhooks to `localhost`, and an MCP server at `/mcp`.
- Prometheus metrics, Grafana dashboards and alert rules in [`monitoring/`](monitoring), and a
  [Helm chart](deploy/helm/railhook).

## Docs

- [Documentation](https://railhook.io/docs/) and [API reference](https://railhook.io/docs/api-reference/)
- [Changelog](CHANGELOG.md), [Upgrading](UPGRADING.md)
- [Architecture](docs/ARCHITECTURE.md), [Operations](docs/OPERATIONS.md),
  [Contributing](CONTRIBUTING.md), [Security](SECURITY.md)

## License

[MIT](LICENSE). Self-hosted has every feature, with no licence key. Third-party notices:
[`NOTICE`](NOTICE), [`docs/licenses/`](docs/licenses/).

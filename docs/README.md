# Railhook documentation

Two surfaces, split by who is reading. Everything here serves someone evaluating the project or
running it themselves. Documentation for someone with the product **open** — transformations,
PII masking, alerts, endpoint security, ordering — lives in the app at `/docs`, next to the
screens it describes.

Nothing is written in both places.

## Understanding it

- **[Architecture](./ARCHITECTURE.md)** — the two directions, the shared attempt lifecycle,
  Claims and fence tokens, the admission order, ordering, the data model, the consistency model
  and the failure modes. Start here.
- **[`CONTEXT.md`](../CONTEXT.md)** — the domain vocabulary. Every term carries a list of
  near-synonyms deliberately not used. Read it before naming anything.

## Running it

- **[Self-hosted deployment guide](./SELF_HOSTED_GUIDE.md)** — hardware sizing, ports, pre-flight
  checks, four installation methods, the full configuration reference, TLS and mTLS.
- **[Operations](./OPERATIONS.md)** — the runbook: common failures, monitoring, backup and
  restore, scaling, upgrades, and the known limitations.
- **[Observability](./guides/observability.md)** — what every metric means, the thirteen alerts
  the chart ships, and the three signals to alert on if you only pick three.
- **[Data retention and export](./guides/data-retention.md)** — what is kept and for how long,
  why successful attempts expire sooner than failures, and how GDPR export works.
- **[Static egress IP](./guides/static-egress-ip.md)** — giving your customers one address to
  allowlist, at the network layer.

## Securing it

- **[Access control and tenancy](./guides/rbac-and-tenancy.md)** — the two independent
  mechanisms, why the four roles are not a ladder, and what `@TenantId` does not cover.
- **[`SECURITY.md`](../SECURITY.md)** — supported versions and how to report a vulnerability.

## Adopting it

- **[How Railhook compares](./guides/comparison.md)** — a feature-by-feature table
  against Svix, Hookdeck and Convoy, with the gaps included rather than omitted.
- **[Migrating from Svix, Hookdeck or Convoy](./guides/migrating-from-other-providers.md)** —
  concept mapping, why your receivers probably do not change, and an honest list of what
  Railhook does not have.
- **[`ROADMAP.md`](../ROADMAP.md)** — the gaps, why they matter, and roughly in what order.
- **[`UPGRADING.md`](../UPGRADING.md)** — breaking changes, per release.

## Reference

- **[API reference](./api-reference.html)** — Redoc over the committed `openapi.yaml`. The same
  spec is served live at `/swagger-ui.html` when `SWAGGER_ENABLED=true`, and is rendered
  in-app at `/docs`.
- **[Releasing](./RELEASING.md)** — maintainer checklist.
- **[Third-party licences](./licenses/README.md)** — generated SBOMs and licence reports.

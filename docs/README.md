# Railhook documentation

Two audiences, two places. Nothing is written in both.

## Using Railhook

The product documentation lives at **[railhook.io/docs](https://railhook.io/docs/)**, in English
and Ukrainian.

- **[Quickstart](https://railhook.io/docs/start/quickstart/)** — install, register, send a first event.
- **[Self-hosting](https://railhook.io/docs/self-hosting/overview/)** — requirements, Docker install,
  domain and HTTPS, [configuration](https://railhook.io/docs/self-hosting/configuration/),
  [upgrade and backup](https://railhook.io/docs/self-hosting/upgrade-backup/),
  [monitoring](https://railhook.io/docs/self-hosting/monitoring/),
  [Kubernetes](https://railhook.io/docs/self-hosting/kubernetes/).
- **[Organizations and roles](https://railhook.io/docs/platform/organizations-rbac/)** ·
  **[Observability](https://railhook.io/docs/platform/observability/)** ·
  **[Data retention](https://railhook.io/docs/resources/data-retention/)** ·
  **[Static egress IP](https://railhook.io/docs/resources/static-egress-ip/)**
- **[How Railhook compares](https://railhook.io/docs/resources/comparison/)** ·
  **[Migrating from Svix, Hookdeck or Convoy](https://railhook.io/docs/resources/migrating/)**
- **[API reference](https://railhook.io/docs/api-reference/)** — rendered from the committed
  [`openapi.yaml`](../openapi.yaml).

## Working on Railhook

For contributors and for operators who want the internals.

- **[Architecture](./ARCHITECTURE.md)** — the two directions, the shared attempt lifecycle, Claims
  and fence tokens, ordering, the data model, the consistency model and the failure modes.
- **[`CONTEXT.md`](../CONTEXT.md)** — the domain vocabulary. Read it before naming anything.
- **[Operations](./OPERATIONS.md)** — the runbook: common failures, backup and restore, scaling.
- **[Releasing](./RELEASING.md)** — maintainer checklist.
- **[`UPGRADING.md`](../UPGRADING.md)** — breaking changes, per release.
- **[`CHANGELOG.md`](../CHANGELOG.md)** · **[`ROADMAP.md`](../ROADMAP.md)** ·
  **[`SECURITY.md`](../SECURITY.md)** · **[`CONTRIBUTING.md`](../CONTRIBUTING.md)**
- **[API reference (Redoc)](./api-reference.html)** — the same spec, offline.
- **[Third-party licences](./licenses/README.md)** — generated SBOMs and licence reports.

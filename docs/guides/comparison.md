# How Railhook compares

Kept out of the README on purpose: a comparison table is the kind of thing that
grows, dates badly, and pushes the one command a reader actually needs below the
fold. It lives here so it can be as detailed as it wants to be.


The honest version. Railhook is younger than Svix, Hookdeck and Convoy, and
there are things they do that it does not — the gaps are listed here rather
than left for you to find during an evaluation, and
[`ROADMAP.md`](../../ROADMAP.md) keeps that list current.

|  | Railhook | Svix | Hookdeck | Convoy |
|---|---|---|---|---|
| Self-hosted with every feature, no licence key | **yes** | open-source server, portal is the hosted product | SaaS (Outpost is the OSS piece) | open source, some features are paid |
| Sending webhooks | yes | yes | yes | yes |
| Receiving and forwarding webhooks | yes | — | yes | yes |
| Standard Webhooks signatures | yes | yes | — | own scheme |
| Secret rotation with an overlap window | yes | yes | yes | yes |
| Per-endpoint FIFO ordering | **yes** | yes | — | — |
| Replay as a new obligation, not a re-send | **yes** | re-send | re-send | re-send |
| Built-in request bin and localhost tunnel | **yes** | Svix Play | CLI | CLI |
| Workflow builder beyond transformations | **yes** | — | — | — |
| Delivers anywhere but an HTTP endpoint | **no** | — | queues, via the gateway | — |
| Customer-facing app portal | **no** | yes | — | Portal Links |
| SSO (SAML / OIDC) | **no** | yes | yes | yes |
| OpenTelemetry traces | **no** | yes | — | — |
| Custom roles, per-resource scoping | **no** | yes | yes | roles |
| Per-subscription filtering | **no** — project-level only | yes | yes | yes |
| Batching, static egress IPs, PagerDuty channel | **no** | varies | varies | varies |
| Terraform provider, Go SDK | **no** | yes | yes | yes |

<sub>Competitor columns reflect their public documentation at the time of
writing and are not a substitute for reading it. Corrections welcome as a PR.</sub>

**A second gap worth naming before you find it**: Railhook delivers over HTTP and nothing
else. Hookdeck's Outpost — the closest open-source comparison, and the same
self-hosted-plus-managed shape — also delivers to AWS SQS, S3 and EventBridge, GCP Pub/Sub,
RabbitMQ and Kafka, and puts "destination types" first in its own comparison table. If your
receivers are queues rather than services, that is the difference, and no amount of retry
ladder makes up for it.

**The one gap that matters most** is the app portal — a surface a customer
embeds in their own product so *their* users can register an endpoint, see why
a delivery failed, and replay it. That is the thing people pay Svix for, and
Railhook has only a shareable single-event debug link. It is the top item on
the roadmap.

**Migrating is cheaper than it looks.** Railhook implements Standard Webhooks
exactly, so a receiver already using a Svix or Standard Webhooks library keeps
working with a new secret and a new URL — nothing else changes. Both platforms
can run side by side with no cutover window; see
[the migration guide](migrating-from-other-providers.md).


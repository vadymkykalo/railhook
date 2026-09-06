# Migrating from Svix, Hookdeck or Convoy

Two things decide how hard a webhook platform is to leave: whether your *receivers* have to
change, and whether your *concepts* survive the move. This guide covers both, honestly — the
last section lists what Railhook does not have, because finding that out after the migration is
worse than reading it now.

## The receiver usually does not change

Railhook implements the
[Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks) convention exactly:

```
webhook-id:        the Delivery id — stable across retries, so a receiver can dedupe on it
webhook-timestamp: unix seconds
webhook-signature: v1,<base64>
```

signed over `{id}.{timestamp}.{body}` with HMAC-SHA256, base64 digest, 300-second default
tolerance. That is the same construction Svix produces, so **a receiver already using an
off-the-shelf Standard Webhooks or Svix verification library keeps working**. What it needs is
the secret in the form those libraries expect — `whsec_` followed by standard base64 — which the
API returns for you. Do not hand a library the stored secret: Railhook stores URL-safe base64
without padding, a different alphabet, which would either fail to decode or decode to different
bytes.

Endpoints are created with `signatureScheme = BOTH` by default, so they receive the Standard
Webhooks headers *and* Railhook's own `X-Signature: t=…,v1=…` over `<timestamp>.<body>`. Extra
headers cost a receiver nothing — it verifies the one it knows and ignores the rest — so you can
migrate receivers one at a time, or never.

| Coming from | Your receiver | What to change |
|---|---|---|
| **Svix** | A Svix or Standard Webhooks library | The secret string and the URL. Nothing else. |
| **Convoy** | Convoy's `X-Convoy-Signature` | Point the library at the Standard Webhooks headers, or verify `X-Signature` — both are being sent. |
| **Hookdeck** | Hookdeck's signature header | Same as Convoy. |
| **Stripe-style, hand-rolled** | `t=…,v1=…` parsing | Nothing — `X-Signature` is that shape. |

## Concept mapping

| Railhook | Svix | Hookdeck | Convoy |
|---|---|---|---|
| Organization | Environment | Team / Project | Organization |
| Project | Application | Project | Project |
| **Endpoint** | Endpoint | Destination | Endpoint |
| **Subscription** (endpoint + event type) | Endpoint filter types | Connection ruleset | Subscription |
| **Event** | Message | Event | Event |
| **Delivery** (one obligation, one endpoint) | Message attempt chain | Event attempt | Event delivery |
| **Attempt** (one HTTP request) | Attempt | Attempt | Delivery attempt |
| **Retry Ladder** | Retry schedule | Retry rules | Retry configuration |
| **DLQ** — shown as *Failed Messages* | — | Issues | Dead letter |
| **Replay** — shown as *Time Machine* | Recover / replay | Bookmarks + replay | Batch replay |
| **Source** (a provider you receive from) | — | Source | Source |
| **Destination** (where an incoming webhook goes) | — | Destination | — |
| **Forward** | — | Event delivery | — |
| **Tunnel** (`railhook listen`) | Svix Play | Hookdeck CLI | Convoy CLI |
| Rules engine, Transformations | Transformations | Filters, Transformations | Filters, Functions |

Two mappings are worth dwelling on:

**Delivery vs Attempt.** Railhook separates the *obligation* to get one Event to one Endpoint
from the individual *tries*. Most platforms conflate them, which is why "how many times was this
retried" is ambiguous elsewhere and exact here.

**Replay is not retry.** A retry is the next Attempt on the same Delivery and advances its
Ladder. A replay builds a **new** Delivery from a stored Event, with a new sequence number, and
leaves the original where it is. If you are used to a platform where "replay" re-runs the
original, expect different — and better — semantics around ordering.

## What Railhook has that these do not

Worth knowing before you plan around an absence that is not there:

- **Both directions in one place.** Outgoing to your users' endpoints *and* incoming from
  providers, with the same attempt lifecycle and the same dashboard. Svix does outgoing only.
- **Per-endpoint FIFO ordering**, opt-in, with real gap detection and a timeout so one stuck
  Delivery cannot block an endpoint forever.
- **Secret rotation with an overlap window.** Both the current and previous secret sign during
  the grace period (default 24h), so a receiver rotates on its own schedule.
- **A built-in request bin and tunnel.** Disposable receiving endpoints and `railhook listen`
  are part of the product, not a separate service.
- **A workflow builder** — branch, delay, transform, call HTTP, post to Slack — beyond
  single-step transformations.
- **Self-hosted with nothing gated.** `BILLING_ENABLED` defaults to `false` and there is no
  licence key. Every feature is in the MIT repository.

## What Railhook does not have

- **No app portal for your customers' own users.** You manage your users' endpoints on their
  behalf. There is a shareable single-event debug link, but nothing embeddable. This is the
  largest structural gap against Svix, and it is the thing people pay Svix for.
- **No SSO (SAML or OIDC) and no SCIM.**
- **No OpenTelemetry export.** Prometheus metrics are thorough; there are no distributed traces.
- **Three fixed roles, two API-key scopes.** No custom roles, no per-resource scoping.
- **Filtering is project-level.** The rules engine evaluates a full condition tree, but it hangs
  off Event ingestion — a Subscription cannot carry its own filter, and the incoming direction
  has no filtering at all.
- **No batching, no static egress IPs, no PagerDuty or OpsGenie channel, no cold-storage
  archival.**
- **SDKs for Node, Python and PHP only.** No Go SDK, no Terraform provider.

`ROADMAP.md` keeps this list current and says which of them are being worked on.

## A migration that does not need a maintenance window

Because a receiver can verify either scheme, the two platforms can run side by side:

1. **Create the shape.** Projects, Endpoints and Subscriptions via the API — see the API
   reference in the dashboard under `/docs`.
2. **Hand each receiver its new secret** in `whsec_` form. Receivers now accept signatures from
   both platforms.
3. **Send to both** from your application for as long as you want confidence. Deliveries are
   idempotent from the receiver's point of view if it dedupes on `webhook-id`, which the Standard
   Webhooks convention already asks it to do.
4. **Watch `delivery_oldest_pending_age_seconds` and the DLQ.** If Railhook's numbers match the
   old platform's, the migration is done.
5. **Stop sending to the old platform.** No cutover moment, no window.

Historical Events do not migrate — Railhook's Time Machine can only replay Events it stored. If
you need the old platform's history, export it before you close the account.

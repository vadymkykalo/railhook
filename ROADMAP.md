# Roadmap

What Hookflow is missing, why it matters, and roughly in what order. Written down because
"is this alive, and does the author know what it lacks?" is the first thing anyone evaluating
a self-hosted platform wants to answer — and because an honest list is more useful than a
feature grid that quietly omits the gaps.

Nothing here is dated. Items move when someone picks them up; the ordering is by how much it
costs a real deployment to live without them.

## Next

### Auto-disable an endpoint that keeps failing

`CircuitBreakerService` is Redis-backed and shared across pods, and it defers — it never
disables. So an endpoint whose owner deleted the receiving service goes on consuming retry
budget, queue depth and delivery rows until a human notices. Svix, Convoy and Hookdeck Outpost
all disable and notify; this is expected baseline behaviour, not a premium feature.

The parts now exist. `AlertEvaluatorService` measures `AlertType.CONSECUTIVE_FAILURES`
against an endpoint's trailing run of outcomes and fires once per crossing, `AlertChannel`
reaches email, Slack and a webhook, and `Endpoint.enabled` stops delivery. What is missing is
the action that joins them — disable, notify, and give the owner a clear way to re-enable once
they have fixed their end.

This entry previously claimed `CONSECUTIVE_FAILURES` "already counts the condition". It did
not: nothing called `AlertService.fireAlert`, all four `AlertType` values were unreferenced
outside their enum, and every rule a user created was inert. The evaluator that closed that
gap is what makes the rest of this item the small piece it was always described as.

### An app portal for the customer's own users

Today a Hookflow customer manages their end-users' endpoints on those users' behalf. There is
no view a customer can embed in their own product for their users to register an endpoint,
see why a delivery failed, and replay it. `SharedDebugLinkController` shares a single event —
useful, but not a portal.

This is the single largest structural gap against Svix, and it is the thing people pay for:
it moves webhook support out of the customer's inbox. It needs a scoped session model for a
non-Hookflow user, an embeddable surface, and enough theming not to look borrowed.

## Known gaps, not yet scheduled

Each of these is a real absence, listed so nobody has to discover it mid-evaluation.

**SSO — SAML and OIDC.** Not implemented, and deliberately not advertised:
`V059__drop_unimplemented_sso_feature_flag.sql` removed the plan flag that claimed it. A hard
blocker for larger organizations.

**OpenTelemetry.** Prometheus metrics are thorough; there is no trace export, so a slow
delivery cannot be followed across ingest → Kafka → attempt. Correlation ids exist and would
carry it.

**A rolling upgrade is untested.** CI installs the last release, upgrades it in place and
checks the data survived, which is what a Compose deployment does. It never runs two versions at
once, so a migration that breaks the previous release's code while it is still serving would
pass. `docs/OPERATIONS.md` argues the current migrations are safe; nothing verifies it.

**RBAC granularity.** Three fixed roles (`OWNER` / `DEVELOPER` / `VIEWER`) and two API-key
scopes (`READ_WRITE` / `READ_ONLY`). No custom roles, no per-resource scoping.

**The operator back-office is thin.** `/api/v1/admin/**` lists and searches organizations, shows
one with its plan and counts, and suspends or reinstates it — enough to answer a support question
and act on an abuse report without psql. What it does not have: usage and delivery history per
tenant, a way to adjust a quota outside the plan catalog, or a read-only support view of a
customer's own screens.

**Terraform provider, and SDKs beyond Node/PHP/Python.** No Go SDK. The three that exist cover
the send-an-event / manage-endpoints / verify-a-signature path and offer a generic
authenticated-request escape hatch for the rest.

**Outgoing endpoints have no HTTP auth types.** `IncomingDestination` carries an `authType`
(bearer, basic, and so on); `Endpoint` does not, so an outgoing endpoint that needs
authentication is limited to a static custom header on the subscription. The asymmetry is
accidental.

**Filtering is project-level only.** The rules engine evaluates a full condition tree, but it
hangs off event ingestion. A subscription cannot carry its own filter, and the incoming
direction has no filtering at all — every incoming event goes to every enabled destination.

**HTTP is the only way out.** A Delivery reaches an Endpoint over HTTP or it does not go.
Hookdeck's Outpost — the closest open-source comparison, Apache-2.0 and the same
self-hosted-plus-managed shape — also delivers to AWS SQS, S3 and EventBridge, GCP Pub/Sub,
RabbitMQ and Kafka, and makes "destination types" the first row of its own comparison table.
For a customer whose receivers are queues rather than services, no retry ladder substitutes.

The seam is closer than it looks: `AttemptRunner` is already generic over the store, and
`Finalization` — Succeeded, Deferred, Retry, Abandoned, TerminallyFailed — says nothing about
HTTP. What is HTTP-shaped is narrower and nameable: a `WebClient` on `RequestSpec`, an
`Integer statusCode` on `AttemptRecord`, a URL on `AttemptContext`, and `RetryPolicy`'s
`408 or 429 or 5xx`. The questions that decide the design are not those, though — they are
whether a signature means anything without HTTP headers, and what a rate limit and a circuit
breaker measure against a broker that accepts everything.

**No batching**, no static egress IPs, no PagerDuty or OpsGenie channel, no cold-storage
archival. Archival has no object store behind it either: MinIO used to sit in the Compose
file with nothing consuming it, and was removed rather than left there implying a feature.

## Deliberately not planned

**A hosted-only tier.** Self-hosting gets every feature, with no licence key and nothing
gated. `BILLING_ENABLED` defaults to `false`, which is what makes that true. We sell hosting,
not features — see the README's "Is this really MIT?" for the full argument.

**A plugin system.** Transformations, the rules engine and the workflow builder cover the
cases a plugin API would, without asking operators to trust third-party code inside their
delivery path.

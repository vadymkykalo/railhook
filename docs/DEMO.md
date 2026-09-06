# Public demo instance — plan (deferred)

**Status: not deployed. This is a plan, not a live URL.** What it needs is
hosting, not code: a reachable host, a DNS record and a certificate. Nothing in
this document is blocked on the platform — the seeding, the read-only access
model and the isolation rules below are all expressible with what already
ships. A small VPS or a free-tier cloud VM is enough; see "Sizing".

## Why this is worth doing (and why it was scoped carefully)

A public read-only demo is the highest-leverage marketing surface for a
UI-heavy product like this — right now `docs/screenshots/deliveries.png` is a static screenshot
doing all the persuasion. But a public demo with live cross-tenant data
leakage would actively damage trust rather than build it, so this plan does
not proceed until two preconditions are both true:

1. **The tenancy/IDOR fixes must be in the tree.** Confirmed present:
   `AuthContext.validateProjectAccess`, the `@RequireOrgAccess` /
   `OrgAccessAspect` org-scoping check, and the API-key project-scope
   interceptor.
2. **The demo must be read-only and seeded, not a live shared write surface.**
   A demo where two anonymous visitors can see or modify each other's data is
   a *new* tenancy bug even with tenancy scoping fixed, if it isn't designed
   for isolation from the start (see "Isolation model" below).

## Proposed design

### Access model — no registration, no shared write state

- One demo organization, one demo project, pre-seeded with realistic sample
  data (endpoints, a handful of event types, delivery history spanning
  success/retry/DLQ states so the dashboard doesn't look empty).
- A single shared read-only JWT (or a "Continue as demo viewer" button that
  mints a short-lived, scoped token server-side) — **not** open registration.
  Open registration on a public demo is how you end up moderating spam
  accounts and, worse, arbitrary user-supplied endpoint URLs used for SSRF
  probing (`webhook.allow-private-ips` / `allowed-hosts` in
  `application.yml` already guards against this in general, but a demo is
  exactly the surface someone will try it against first).
- RBAC: mint the demo token as `Viewer` role (`AuthContext.requireWriteAccess()`
  / `requireOwnerAccess()` already reject write operations for `Viewer` —
  reuse this rather than build new demo-specific logic). Write-shaped
  endpoints (create endpoint, rotate key, delete project, ...) return 403
  through the existing RBAC path with no demo-specific code branch, which is
  the point — it's the same enforcement every other tenant gets, not a
  parallel "demo mode" that could itself have gaps.
- The one interactive write worth allowing is **"send a test event"** against
  a demo-only endpoint that echoes into a request bin (the existing Webhook
  Capture feature already does this) — that's the actual "try it" moment and
  it's scoped to data nobody else's demo session can see or corrupt.

### Isolation model

- Demo data lives in its own organization/project, exactly like any other
  tenant — no code path bypasses the standard org/project scoping to serve
  it. This is deliberate: the demo should exercise the *real* isolation
  guarantees, not a special-cased read path that could
  drift out of sync with them.
- A scheduled job resets demo data on an interval (e.g. every 30–60 minutes):
  truncate and re-seed, so no visitor can leave graffiti for the next one and
  accumulated junk data doesn't slowly degrade the "wow" of the first
  impression.
- Cross-tenant check to run before calling this live (see Verification
  below): confirm the demo viewer token cannot read/write any project other
  than the seeded demo project — exactly what
  `webhook-platform-api/src/test/java/com/webhook/platform/api/OrganizationIsolationTest.java`,
  `.../security/ProjectScopeEnforcementIsolationTest.java` and
  `.../TestEndpointIsolationTest.java` already assert for every tenant, so
  the demo token is just one more fixture through the same suite rather than
  a new isolation mechanism to trust.

### Resource caps (hard, not advisory)

- Separate deployment (its own `docker compose` stack or a small dedicated
  namespace if run on the same cluster as anything real) so a spike in demo
  traffic can't degrade a real tenant's delivery latency.
- Rate limit at the reverse proxy in front of it (e.g. 60 req/min/IP) in
  addition to the app's own Redis-backed limiting — the demo is a target for
  scripted abuse in a way a normal deployment isn't, precisely because its
  URL is public and unauthenticated.
- CPU/memory limits per the `deploy.resources.limits` blocks already in
  `docker-compose.yml` — no change needed there, just make sure they're set
  for whatever compose profile the demo runs.
- No outbound webhook deliveries to arbitrary attacker-supplied URLs: either
  disable the "add a custom destination" flow entirely for the demo viewer
  role, or restrict it to the built-in request-bin endpoint only. This is the
  one place a "read-only" demo could still be turned into an SSRF/DDoS
  amplification vector if left unrestricted.

### Sizing

Smallest viable footprint per `docs/SELF_HOSTED_GUIDE.md`'s hardware sizing
section — this is a demo, not a production tenant, so the smallest documented
tier is enough: 2 vCPU / 4 GB RAM is comfortable for Postgres + Redis + Kafka
(single broker, `KAFKA_NUM_PARTITIONS` turned down since demo throughput is
trivial) + API + worker + UI, all in one `docker compose -f
install.sh | bash --domain demo.example.com` on a small VPS.

## What would make this "DONE" instead of deferred

1. Deploy the stack above on any reachable host (a $5–10/mo VPS is enough).
2. Point a subdomain at it (e.g. `demo.example.com`) and link it from the
   README next to `docs/screenshots/deliveries.png`.
3. Run the cross-tenant check from "Isolation model" above against the live
   instance from a second (real, non-demo) account and paste the result here.
4. Confirm the data-reset job is actually scheduled and running.

None of the four steps above require code changes beyond what's already in
this tree (the seeding script and reset job are the only new pieces, and both
are ordinary application code, not security-sensitive) — this is an
infrastructure/deployment task, tracked here so it isn't lost, not a
"needs another engineering task" blocker.

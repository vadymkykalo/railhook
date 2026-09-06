# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Spring Boot 4.1.1.** 3.5.16 was the final OSS release of the 3.5.x line, and the fixes for
  the five CVSS 9.8/9.1 CVEs in `spring-core`/`spring-web` 6.2.19 and `spring-security` 6.5.11
  ship only in Framework 7 / Security 7 — there was no patch coming to the line we were on, so
  the nightly `Security SCA` had been failing on vulnerabilities that could not be fixed by
  waiting. Java stays on 17.

  Most of the work was not renaming imports. Boot 4 stopped auto-configuring a technology just
  because its library is on the classpath, and the way that announces itself is silence:
  `flyway-core` without `spring-boot-starter-flyway` starts the application, applies no
  migrations at all, and fails on the first missing table. Kafka's auto-configuration and
  WebClient's each moved into modules only their own starter pulls in — the latter meaning
  `spring-boot-starter-webflux`, which this API only ever wanted for `WebClient`, no longer
  provides one.

  Redisson moves to 4.7.0 for the same reason its predecessor worked: the 3.x starter wires
  itself against Boot 3's module layout. Nothing in the test suite would have caught that —
  the integration tests exclude Redisson's auto-configuration outright and mock every service
  that reaches for Redis — so it was checked against a running stack instead.

- **HTTP stays on Jackson 2**, through Spring's own bridge module and one property. Boot 4
  defaults to Jackson 3, and two DTOs put a Jackson 2 `JsonNode` on the wire; one of them is
  backed by a JSONB column on the `Plan` entity that the schema-validation gate also reads.
  Changing mappers is therefore an entity change with a migration behind it, which is not
  something an upgrade whose purpose is closing CVEs should smuggle in.

  The two directions fail separately, which is worth knowing before someone tidies either
  piece away: without the property, *reading* a body into a `JsonNode` field answers 500 while
  writing one still works — and `EventIngestRequest.data` is such a field, so what breaks first
  is every event the platform ingests. Both the bridge and the property are deprecated or
  easily mistaken for decoration, so the contract is pinned by a test that covers each
  direction rather than left for whoever removes them to rediscover.

### Added

- **An operator back-office.** `/api/v1/admin/**` was one endpoint that rotates encryption keys;
  everything else an operator might need — who is on this deployment, why did this customer's
  deliveries stop, make this one stop — was psql. It now lists and searches organizations, shows
  one with its plan, billing status and project/member counts, and suspends or reinstates it.
  Behind the same platform-admin credential, which no tenant JWT or API key can carry.

- **Suspension that suspends.** `BillingStatus.SUSPENDED` was written by the dunning scheduler
  when a grace period expired and read by nothing at all, so an organization that had stopped
  paying went on ingesting and delivering exactly as before — and an operator had no way to stop
  an abusive tenant except by editing the database. A suspended organization is now refused
  every write, ingest included, with the reason the operator typed; reads keep working so the
  tenant can sign in and be told what happened.

  Stored on the organization rather than in `billing_status`, deliberately: that column belongs
  to the payment state machine, and an abuse suspension recorded there would be lifted by the
  next successful charge. Both actions land in the audit log.

- **A transform node can use the project's transformation library.** The library was
  unreachable from the canvas: a project could build up named transformations and point rule
  actions at them, and then in a workflow had to retype one into a box. Retyping it did not
  work, silently — a saved transformation is `${$.json.path}` run by the engine that transforms
  a delivery payload, the node's own template is `{{field.path}}`, and text written for one is a
  literal string in the other with no error either way. The node now takes either, as an
  explicit choice rather than a guess about what was pasted, and a transformation can be created
  from the node panel without leaving the canvas. A reference that is deleted or disabled fails
  the step: passing the payload through would send a raw event somewhere promised a reshaped
  one and report it as a success.

- **`hookflow admin`** — orgs, org, suspend, reinstate — and `/admin/organizations/{id}/usage`
  behind it, so an operator can see what a tenant has used against their plan. Deliberately no
  page in the dashboard: it is served from the same origin as the API, so a platform-admin token
  in a browser turns any XSS in the tenant dashboard into the deployment's master credential.
  The token is read from the environment or a flag on each invocation and never saved.

- **The create-event node offers the schema registry's event types** as suggestions. A datalist
  and not a select, because emitting a type that has no schema yet is allowed.

### Fixed

- **Two public documents described the conditions they were written under rather than the
  project.** `docs/DEMO.md` opened by explaining that "this sandbox has no way to provision or
  expose long-running public infrastructure", and `load/README.md` had a section headed "What was
  actually verified in this sandbox session" that discussed other agents contending for a Docker
  daemon. Both are read by someone evaluating whether to run this, and neither told them anything
  about it. They now say what is true of the project: the demo needs hosting rather than code, and
  the load harness has proven its scripts but published no numbers.

- **The dashboard stopped doing SEO for somebody else's website.** `hookflow.dev` is not this
  project's domain - it serves an unrelated product - and the shipped UI named it in
  `rel="canonical"`, `og:url`, `og:image`, `twitter:image` and the schema.org block, listed 23 of
  its URLs in `public/sitemap.xml`, pointed `robots.txt`'s `Sitemap:` line at it, and offered
  `sales@` and `support@` there on `/contact`. A canonical is an instruction to a search engine to
  credit the page it names, so every self-hosted install was issuing that instruction on every
  page, and anyone who took the support address wrote to a stranger.

  The same constant was the default `EMAIL_FROM` in `.env.dist`, `docker-compose.yml`,
  `application.yml`, `EmailService`, both Helm values files and the monitoring stack's
  Alertmanager config. Mail from a domain you do not own fails SPF and DKIM at the receiver, so
  an operator who turned `EMAIL_ENABLED` on without noticing got verification mail silently
  refused - and a user staring at a screen telling them to check an inbox nothing would reach.

  Nothing is replaced with a different constant. What a deployment publishes about itself now
  comes from the deployment:

  - `VITE_SITE_URL` gives the public origin. Unset - the default, and what every private
    dashboard wants - the canonical follows the browser's own origin and index.html carries no
    absolute self-reference at all, the JSON-LD block included. `scripts/prerender.mjs` needs the
    variable set, or it would freeze its throwaway local server's address into the static HTML.
  - `VITE_CONTACT_DOMAIN` gives the `/contact` mail addresses. Unset, those two cards are not
    rendered: a deployment someone runs for their own company has no sales desk, and an address
    that reaches nobody is worse than an absent one. The issues and documentation cards, which
    are true everywhere, stay.
  - The sitemap generator takes `SITE_URL`. A sitemap must carry absolute URLs, so the committed
    copy names `example.com` - IANA-reserved, and unable to become anyone's product - as do every
    placeholder address and the Helm ingress host.

  One trap for whoever edits `index.html` next: the canonical cannot be written as a relative
  `"/"`. Vite treats `href` on a `<link>` as an asset reference and reads it, so a root-relative
  canonical fails the build with `EISDIR` on the public directory. It goes through the same
  build-time placeholder as the rest.

### Security

- **CodeQL.** The security set covered known CVEs in dependencies from two angles (Trivy over the
  built images on every push, OWASP Dependency-Check nightly with a reviewed suppression file) and
  bug patterns per method (SpotBugs), but nothing asked the taint-tracking question: whether
  attacker-controlled input reaches a sink. It runs nightly, on pull requests into `develop`, and
  on `main`/`release/**`/`hotfix/**`; results land in the Security tab.

  It does not fail the build on a finding, on purpose. A first CodeQL run over an existing
  codebase reports a backlog, and a gate that is red the day it arrives is a gate somebody
  switches off. Raising `fail-on` is the follow-up once that backlog is triaged - and note that
  making it a *required* check on `main` before it has completed there once leaves every pull
  request pending forever.

- **Tomcat 11.0.25.** Boot 4.1.1's BOM manages 11.0.24, which carries three CRITICALs -
  CVE-2026-65182 (security-constraint bypass), CVE-2026-65905 (authentication bypass) and
  CVE-2026-68525. They are in the container that terminates every request the API serves, so
  the CI container scan was failing the build on `develop`, correctly.

  Worth knowing before anyone tidies this away: setting `<tomcat.version>` alone does nothing
  here. This build *imports* `spring-boot-dependencies` rather than inheriting from
  `spring-boot-starter-parent`, and an imported BOM resolves its own `${tomcat.version}`
  against its own properties - the override is simply not read. The dependency tree still said
  11.0.24 with the property set. The three artifacts are therefore pinned by explicit
  `dependencyManagement` entries declared *before* the BOM import, because
  `dependencyManagement` takes the first declaration it finds. The property remains, now read
  by those entries, so the version is still stated once.

- **Email verification is enforced on the server.** It was a component in the dashboard:
  `VerificationGate.tsx` greyed out the buttons, and login refused only `DISABLED`, so an
  unverified account got an ordinary token and had the whole API with curl. Writes now require
  a verified address; reads stay open, because the screen telling the user to check their mail
  is a read. Inert where verification is meaningless — with mail off, registration marks the
  account verified on the spot, so a self-hosted instance sees no change.

- **A CAPTCHA on registration**, off by default. The auth rate limit is per address, and an
  address is the one thing a signup farm has plenty of. Cloudflare Turnstile out of the box;
  hCaptcha speaks the same siteverify shape, so the URL is what picks between them. Verification
  fails closed — an unreachable provider refuses the registration rather than waving it through,
  because a CAPTCHA that silently stops checking is the state it was added to prevent. A
  deployment that configures none gets a verifier that accepts everything, which is the honest
  shape of a control that is switched off and the right default for self-hosting.

- **A hosted deployment refuses to start half configured.** `BILLING_ENABLED` is the whole of
  what separates hosted from self-hosted, which also means one unset variable away from an
  open, unbilled, unverified service that starts happily. In production it now requires
  `EMAIL_ENABLED=true`, a CAPTCHA and a real payment provider: billing on with mail off is a
  paid tier behind an address nobody proved they own, billing on with no challenge is a free
  tier anyone can mint, and billing on with the no-op provider is plans enforced and never
  charged for.

### Fixed

- **A dialog taller than the window had no reachable edges.** `DialogContent` is centred with
  `translate-y-[-50%]` and had no height cap and no overflow, and Radix freezes the page behind
  an open dialog — so the endpoint form opened with its title above the top of the screen and
  Save and Cancel below the bottom, with nothing to scroll. Six call sites had already hit this
  and pasted `max-h-[85vh] overflow-y-auto` onto their own dialog at three different heights,
  which is what kept it alive: the bug looked fixed everywhere anyone had looked. The cap is
  now the primitive's, in `dvh` rather than `vh`, and a test fails if a call site starts setting
  its own.

- **Two of the three incident tiles counted one page, not the project.** "Open" came from a
  server count; "Investigating" and "Critical" were `filter()` over the twenty rows on screen.
  A project with more open incidents than fit on a page showed "Critical: 0" with a critical
  incident open on page two — the tile went quiet exactly when there was too much going on to
  fit. All three are now counted server-side over the project.

- **A public page starts at its own top.** `createBrowserRouter` leaves the scroll offset alone
  across a navigation, so following "Pricing" from halfway down the home page opened `/pricing`
  somewhere in its FAQ. A hash still wins, or every anchor in the header would break.

- **No input in the workflow node inspector had an accessible name.** The label and the control
  were siblings with nothing joining them, so every field — the URL a workflow posts to, the
  endpoint it delivers through — was announced as an unlabelled textbox and clicking a label
  focused nothing.

- **The pricing page said "requests / second"** where only ingest is metered per plan, which is
  what the billing page had already been corrected to say.

- **`WEBHOOK_ALLOWED_HOSTS` reaches the connection it exempts.** The admission check honoured
  the list and the post-connect check — the one that closes the DNS rebinding window — had no
  idea it existed, so an operator who allow-listed an internal host watched every delivery to
  it die at the TCP layer with nothing in the configuration to explain why. Both halves now
  answer through one function. It failed closed, so this was a knob that did nothing rather
  than a hole.

- **The plan catalog answers an anonymous caller.** `/api/v1/billing/plans` is permitted and
  a comment called it public; it returned 500 to anyone without a token and always had, because
  nothing set a tenant scope and the resolver refuses to guess. Only the dashboard called it,
  from behind a login, which is why nobody noticed.

- **A page's crash stays inside that page.** The app had one error boundary, at the root, so a
  render error anywhere replaced the whole dashboard and the only way back was a reload.

- **`DeliveryResponse.status` is typed as its enum.** The JSON is unchanged — Jackson writes an
  enum as its name — but the published contract now names the five values, the generated
  TypeScript narrows from `string` to a union, and the locale ratchet can finally cover the
  most-rendered status label in the product.

### Added

- **Search on the members list**, which the API serves as one unpaginated array — so finding
  someone meant scrolling.

- **SDK unit tests run on every pull request.** They ran on a release tag and nightly, so a
  change that broke one merged green.

- **Five more interpolated locale namespaces are checked against the spec**, after 2.10.0
  shipped four sets of raw translation keys to a customer's screen and guarded only those four.

## [2.10.0] - 2026-09-04

Fifty-five commits since 2.9.1. The theme, if there is one, is closing the gap between what the
platform could already do and what a user could actually reach or see — several features in here
were fully implemented on the backend and inert, unreachable, or invisible.

### Added

**Authentication.** Five gaps, closed together because they are the same question asked five
ways: who is signed in to this account, with what, and how do I take it back.

- **Active sessions, and per-session revoke.** Refresh tokens are self-contained JWTs, so nothing
  anywhere knew how many were outstanding: a user could not see that a laptop they no longer own
  was still signed in, and could not see a CLI device-code grant at all — the credential most
  likely to outlive the machine it was issued to. Settings now lists every live session with its
  client, address and last activity, and ends any one of them. Revoking reaches the access token
  too, via a `sid` claim the JWT filter checks, rather than leaving the device authenticated for
  the remaining quarter of an hour. "Sign out everywhere" reuses the per-user revocation epoch
  that already existed, which is one Redis write for every token at once.
- **API key rotation with a grace window.** `POST /api/v1/projects/{projectId}/api-keys/{apiKeyId}/rotate`
  issues a replacement and gives the outgoing key an expiry — 24 hours by default, `0` to cut it
  off immediately after a suspected leak. Both keys authenticate for the window, so a rollover is
  no longer a create-then-revoke race the customer has to time by hand.
- **An organization switcher.** `GET /api/v1/orgs` has always returned every organization a user
  belongs to and nothing ever called it, because login minted a token for the oldest membership
  and refresh minted the same one again — so accepting an invite to a second organization changed
  nothing you could see. `POST /api/v1/auth/switch-organization` re-issues an access token for a
  membership the caller genuinely holds, with the role from *that* row. The choice is stored on
  the session, so a refresh fifteen minutes later does not undo it.
- **Account lockout on consecutive failed sign-ins.** Progressive — a minute at the fifth failure,
  doubling, capped at fifteen — and every lockout lapses on its own, with a password reset
  clearing it outright. Configured through `AUTH_LOCKOUT_*`.
- **Suspending a member**, for the cases where deleting them is too much.
- **A CLI login you can refuse**, and a tunnel status the compiler checks.

**Incoming direction.** The side of the platform that had been getting less attention.

- **A DLQ you can browse, retry and purge**, matching what the outgoing direction already had.
- **Twilio signature verification**, and `CUSTOM` stops pretending to be a provider.
- **What a Forward actually sent** is now visible, and a new endpoint can pick its signature
  scheme at creation.

**Reliability and limits.**

- **Alert rules are evaluated**, so the rules users create can fire. Every `AlertType` value was
  unreferenced outside its own enum: rules could be created, and none of them ever did anything.
- **A delay node suspends its execution** instead of sleeping on a thread.
- **Per-organization fairness in the worker**, so one organization can no longer take all of it.
- **The compatibility mode the schema registry stored and never once read** is now enforced.
- **Standard Webhooks** was shipped everywhere except where a user could reach it; it is now
  selectable in the UI.
- **Four things the backend could already do and the UI could not ask for.**
- **An onboarding that builds the thing** instead of describing it.

### Changed

- **BCrypt work factor is now 12 and configurable via `AUTH_BCRYPT_STRENGTH`.** Both services that
  hash a password called `new BCryptPasswordEncoder()`, which is the library's 2010 default of 10.
  Raising it is not a migration: BCrypt writes the cost into each hash, so every stored password
  keeps verifying and is rewritten at the new cost the next time it changes. Measured cost is
  about 160 ms per sign-in.
- **MinIO is removed.** It was in the Compose file and nothing consumed it.
- **Seven configuration knobs an operator could turn that changed nothing** now either work or are
  gone.
- **Dead UI code deleted** — what nothing called.

### Fixed

- **Four ways a Forward was lost, unfenced or unrecorded.** The incoming direction's claim
  handling did not match the outgoing direction's, which is precisely the asymmetry
  `AttemptRunner`'s invariants exist to prevent.
- **A broken transformation fails the attempt** instead of delivering a payload with holes in it.
- **A circuit breaker that cannot count says so** — Redis being unreachable is now visible as
  `circuit_breaker_degraded_total` rather than silently failing open.
- **The whole incoming direction was free and unbounded**, and events and deliveries are now
  bounded without requiring billing to be enabled.
- **A quota counter that stayed short for the rest of the month.**
- **The billing states nothing could reach**, and one nobody synced.
- **A WARN schema policy that warned nobody who could act on it.**
- **The SSRF window on the one client a user aims** — alert webhooks — is closed.
- **Masking rules apply on the screens people actually debug on.**
- **The PII card rule missed the shape every payment provider sends**, and the PII preview crashed
  the page it was added to.
- **Endpoint verification survived the edit that invalidates it.**
- **A member could not change their own password**, and an invite could not be delivered.
- **An unreachable SMTP relay no longer reports the whole API as down.** The aggregate health
  indicator read DOWN with `EMAIL_ENABLED=false`.
- **A template-default install could not log in.**
- **One rule for what an omitted field means on update**, applied consistently.
- **Six rail links that looked broken because they went nowhere**, and the chrome a table gets
  before it has anything to show.
- **The Helm chart never came up, and CI could not have noticed** — plus the kind smoke job's
  Kafka pointed its quorum at a Service port that does not exist.
- **`make dev-ui` built one image and started another.**

### Documentation

Substantially expanded, and split by audience: the repository holds what you read while
evaluating or operating Hookflow, the dashboard's `/docs` holds what you read with the product
open. Nothing is written in both places.

- **`docs/ARCHITECTURE.md` rewritten.** It was four diagrams and their captions. It is now
  fourteen diagrams — the data model, the Claim and its fence token, the admission order, the
  delivery state machine, ordering and gaps, tenancy, the production topology — plus prose on the
  consistency model, partitioning and failure modes that previously existed only in people's
  heads.
- **Nine new guides.** In the repository: observability, access control and tenancy, data
  retention and export, static egress IP, and a comparison against Svix, Hookdeck and Convoy with
  the gaps included. In the app: transformations, ordering, endpoint security, PII masking, and
  alerts and incidents — all in both English and Ukrainian.
- **A migration guide** for Svix, Hookdeck and Convoy, which documents something that was true and
  unstated: Hookflow implements Standard Webhooks exactly, so a receiver already using a Svix
  library keeps working with nothing but a new secret and URL.
- **`OPERATIONS.md` known limitations** are their own section rather than buried in the backup
  runbook — including that a Postgres restore does not reconcile Kafka and Redis.
- **The README** gained a capability table, a table of contents and a documentation index while
  getting shorter per section, by cutting what it said twice.
- **`ROADMAP.md`** no longer lists the organization switcher as missing. It shipped.

### Build

- **Three CI tool downloads retry**, and land on disk before being unpacked. A retry cannot rescue
  a `curl | tar` pipe: by the time curl starts over, tar has already read a truncated stream. The
  Helm job failed exactly that way on a clean tree.
- **A test asserting every documentation section renders**, because a guide is registered in two
  files and nothing tied them together — a nav link that opens an empty page produced no error.

## [2.9.1] - 2026-08-29

The other half of the API-reference fix that shipped in 2.9.0.

### Fixed

- **`pageable` was published as a required query object on ten list endpoints.**
  The same defect as the `auth` parameter 2.9.0 removed, from the same cause:
  springdoc read Spring's `Pageable` off the controller signature and published
  the object rather than the three query parameters it actually is. A reader had
  no way to learn that paging is `?page=0&size=20&sort=createdAt,desc`, and the
  document told them a parameter was required that the server never reads.
  Every one of the ten now lists `page`, `size` and `sort`, none of them required.

## [2.9.0] - 2026-08-29

Three defects that were already in production, five listings that queried once per
row, and the structural work that stops each of them recurring. No API change.

### Fixed

- **A Forward could be finalised by an attempt that no longer owned it.** The two
  directions claimed to implement the same fencing contract and did not: Outgoing
  rejects an unfenced Claim against a row carrying a token, Incoming accepted one
  unconditionally. A retry message published before the token existed could
  therefore write over a row a newer attempt had taken — the duplicate forward the
  fence exists to prevent. Both directions now read the predicate from one place.
- **An open circuit breaker left no trace on the incoming direction.** The Runner
  records the refusal deliberately, so an operator looking at why a destination
  went quiet sees the breaker rather than an unexplained gap. Incoming buffered
  that record and applied it during finalisation, where the deferral branch
  returned before reaching it. Outgoing had always written it.
- **Only one of eleven outbox writes carried a correlation id.** Replay, DLQ
  retry, bulk replay, workflow deliveries and the whole incoming direction reached
  the worker with a freshly invented one, so none of them could be traced across
  the two services. Two of those paths also dropped the sequence number and the
  ordering flag from the message.
- **Two dashboard lists did not refresh after the action that changed them.** The
  workflow node panel read endpoints, api keys and subscriptions under keys of its
  own, which no invalidation matched; the event detail page read its deliveries
  under a key `useReplayDelivery` does not touch, so replaying from that page left
  it stale.
- A malformed `from=` or `to=` on the audit log export was silently dropped, so a
  typo returned the unfiltered log and looked like it had worked. It is now a 400.
- **The published API reference described an endpoint nobody could call.** Every
  one of the 187 operations carried a required `auth` query parameter of type
  `AuthContext` — an internal object resolved from the bearer token or the API
  key, never sent by a caller. springdoc read it off the controller signatures and
  published it; it is now hidden, and 834 lines of it left the spec.
- **The reference told readers to call `http://localhost:8080`.** Hookflow is
  self-hosted, so no address is right for everyone: the server is now a
  `{baseUrl}` variable a reader fills in, defaulting to the local one the
  quickstart already has them open.
- Twenty-five operations — workflows, incidents, alerts, the audit log export —
  had a name and no description at all. Every operation now has both.

### Changed

- **Listing is no longer one query per row.** Workflows issued five COUNT
  statements per workflow — two of them asking the same question twice — rules
  three queries each, and transformations, subscriptions and incoming destinations
  one apiece. Fifty rows meant a hundred and one round trips; each listing now
  resolves what the page needs before mapping it.
- Sixteen ownership guards that answered 403 were removed. They were unreachable:
  the entity carries a tenant discriminator, so another organization's row is a
  404 before the comparison runs. Behaviour is unchanged; three unit tests that
  pinned the dead branch now assert what actually happens.
- The attempt pipeline is split along the seams it already had names for —
  ordering, signing, destination authentication, store construction, the polling
  loop, the cluster-wide sweep lock — and the row transitions live on the row.
  Nothing about how a Delivery or a Forward behaves changed; 1381 tests and every
  ratchet pass unchanged.
- The 137 references to ADRs and runbooks deleted in `79758b3` are gone from the
  code, along with the javadoc that had grown to 80% of `AttemptStore` and two
  blocks copied verbatim into 31 and 11 files.

### Added

- A `Clock` bean in both services, so the billing month boundary and the secret
  rotation grace window are tested rather than waited for.
- Direct tests for both Attempt Stores, which had only ever been reached through
  the services that build them.

## [2.8.0] - 2026-08-28

Release-pipeline repairs and dependency updates. No product change.

### Fixed

- **The Node SDK publish now works from the release itself.** npm's Trusted
  Publisher validates the OIDC token against the workflow that *entered* the
  run, not the file containing the job — so called as a reusable workflow from
  `release-cli.yml`, a publisher trusting `publish-sdks.yml` matched nothing and
  every release got a bare `404 Not Found - PUT`. Both 2.6.1 and 2.7.0 had to be
  published by hand. `publish-sdks.yml` now owns its tag trigger, which makes the
  entry workflow the one npm trusts, and `workflow_call` is gone so it cannot be
  wired back the old way.
- **Dependabot no longer offers netty across its minor line.** The root pom
  overrides what Spring Boot manages purely to take CVE fixes within 4.1, and a
  grouped bump to 4.2.17 stopped the build compiling outright. The config now
  records what the comment above the property already said.

### Changed

- GitHub Actions across all workflows, `@types/node`, `@testing-library/jest-dom`
  and the PHP SDK's phpstan constraint updated.

## [2.7.0] - 2026-08-28

Hookflow now speaks [Standard Webhooks](https://www.standardwebhooks.com) as well as
its own signature scheme, so a receiver can verify with a library they already
have instead of reading our documentation.

### Added

- **Standard Webhooks signatures.** Endpoints receive `webhook-id`,
  `webhook-timestamp` and `webhook-signature` alongside the existing
  `X-Signature`, controlled by `Endpoint.signatureScheme` — `BOTH` by default.
  Unknown headers cost a receiver nothing, so every existing endpoint keeps
  working untouched while a new one can reach for one of the convention's nine
  language libraries from day one. `LEGACY` and `STANDARD` are there for anyone
  who wants exactly one.

  Three details that are easy to get subtly wrong, and were not: the signed id is
  the *delivery* id, stable across every attempt and so usable for deduplication,
  where the event id would collide across a fan-out; the signing primitive takes
  key **bytes**, because the reference libraries HMAC with base64-decoded material
  and round-tripping those through a String mangles every byte above `0x7F`; and
  `EndpointResponse` now carries `standardWebhooksSecret`, the `whsec_`-prefixed
  form, because stored secrets are URL-safe base64 without padding — a different
  alphabet from the one those libraries decode, which would otherwise reject every
  delivery with no clue why.

- **`verifyStandardWebhook` in all three SDKs** (Node, Python, PHP), each with the
  same cases: a reference signature, case-insensitive headers, either secret
  verifying through a rotation window, a replay rejected despite a still-valid
  signature, a signature lifted from another message, a tampered body, missing
  headers, and an unknown signature version.

- **`ROADMAP.md`**, naming what the project lacks — SSO, OpenTelemetry, RBAC
  granularity, per-subscription filtering and the rest — rather than leaving an
  evaluator to discover it.

### Fixed

- **The refresh cookie outlived its token**, pinned at seven days while the token
  expires in one; for six of those days the browser presented a token the server
  had already rejected. Its max-age now derives from the token's lifetime.
- **The outbox age gauge queried the database on every metrics scrape**, from every
  replica, on the management port that deliberately sits outside the auth chain.
  It is sampled on the publisher poll instead, and the gauge is a memory read.
- **Retrying out of the DLQ reset `attemptCount` to 0**, so the attempt it recorded
  collided in number with one already on the record and "the latest attempt"
  stopped being well defined. The count carries forward; `maxAttempts` is raised.
- **`purgeAllDlq` was one unbounded DELETE**, which with the foreign key restored in
  2.6.1 cascades into every attempt row and held locks across all of them for a
  single transaction. Batched.
- **A dead `ApiKeyAuthCacheService`** that would have thrown or filtered to the wrong
  organization had anything injected it. Removed; the correct implementation
  already lives in `ApiKeyAuthenticationFilter`.
- **The release workflow could not be re-run without rewriting its tag**, and failed
  to start at all when a called workflow asked for a permission its caller had not
  granted. Both fixed, the first with a `workflow_dispatch` that reads the tag
  everywhere rather than the branch it was started from.

### Changed

- The generic raw-hex HMAC verifier documents what it cannot promise: that shape
  signs the body alone, so a captured request stays verifiable for as long as the
  secret lives, and no verifier can supply a property the provider's scheme lacks.
  The replay window that does bound it is now configurable.

## [2.6.1] - 2026-08-28

A repair release. 2.6.0 shipped without its UI image, which broke the one-line
install for every new user, and the audit that found it turned up seven runtime
bugs that lose work silently.

### Fixed

- **The UI image for 2.6.0 was never published, so `install.sh` was broken.** Its
  build died under QEMU: buildx runs the build stage once per target platform, so
  the `linux/arm64` leg ran Chromium for the prerender under emulation, blew a 30s
  budget and killed the job after sixteen minutes. The build stage is now pinned to
  `$BUILDPLATFORM` and runs natively once; only the nginx runtime stage stays
  per-arch. Takes roughly sixteen minutes off every release as a side effect.
- **npm and Packagist had been publishing nothing since v2.3.0 and February
  respectively**, the first because a token expired, the second because the step
  posted an update call naming a repository Packagist has never heard of and
  checked neither the exit code nor the response. npm moves to Trusted Publishing;
  the PHP SDK is now pushed to its split repository as part of the release.
- **A new `verify-release` job** asks GHCR, npm, PyPI and Packagist what is
  actually published and fails when any of them disagrees with the tag. The
  existing version guard compares files in the repository to each other, so none
  of the above was visible to it.
- **The Helm chart could never have brought up its UI**: the container port and
  both probes said 80 for an nginx that listens on 5173, the chart-wide security
  context handed stock nginx a uid with no writable cache or pid path, and the
  image proxies to a hostname the chart did not create. `helm lint` now runs on
  every pull request rather than only on a tag, which is how all of that reached a
  release; on its first run it found a fifth fault, a duplicate map key in a
  ConfigMap that `kubectl` rejects. Chart image tags now default to the chart's
  appVersion instead of `latest`.
- **A saturated endpoint stopped delivery for every tenant.** The per-endpoint
  concurrency permit was acquired with a 100-*second* wait where 100 milliseconds
  was meant, so one slow endpoint drained the outgoing pool and the worker paused
  every Kafka listener.
- **Workflow triggers were dropped silently and then locked the project out.** The
  executor's rejection handler did not throw, so the caller could not tell the task
  had been discarded: the outbox row stayed `PROCESSING` with nothing to reclaim it,
  and the per-project in-flight counter leaked one per rejection until every future
  trigger for that project deferred forever.
- **Two paths delivered the same webhook twice.** The outgoing retry read its
  fencing token out of the row rather than carrying it, so every redelivery of a
  Kafka message agreed it owned the row; the incoming side had no fence at all in
  `finalise`, letting a stuck-swept attempt overwrite a live claim, queue a
  successor, and discard a concurrent success.
- **FIFO ordering degraded to nothing being delivered.** A terminal failure — a
  non-retryable 4xx, a disabled endpoint, an SSRF rejection — never released the
  ordering cursor, so it stuck permanently and every later delivery for that
  endpoint was held behind it.
- **Password reset did not revoke live sessions**, leaving an attacker's access
  token valid for its full lifetime while the owner believed they had locked them
  out. Member removal and role change had the same gap.
- **A share token could be read across projects.** Listing an event's debug links
  validated the project in the path but loaded links by event id alone, defeating
  the API-key project confinement that exists for exactly this case.
- **`/actuator/health` published component detail anonymously**, including the
  database product and version, through the one public port.
- **A foreign key dropped by accident in V052** left delivery attempts orphaned,
  request and response bodies included, when a DLQ purge removed their deliveries.

### Changed

- The revocation epoch in Redis now expires instead of accumulating one key per
  user forever, and login picks a user's oldest organization rather than whichever
  the database happened to return first.

## [2.6.0] - 2026-08-27

No changelog entry was written at the time; this and the 2.5.0 entry below were
reconstructed from the release history afterwards, which is what the CHANGELOG
check added in 2.6.1 exists to prevent happening again.

### Added
- **`install.sh` — the install is one command.** It checks the machine (Docker,
  Compose in either spelling, memory, disk, ports), writes a directory holding a
  Compose file pinned to the latest release and a `.env` with locally generated
  secrets, verifies that configuration, and starts the stack. The previous
  instructions were three commands and two `curl`s, one of which fetched
  `.env.dist` — whose secrets are public, because it lives in a public
  repository. Anyone who followed the README verbatim deployed with an
  encryption key and JWT secret published on GitHub.
- **`--domain` puts it on a domain with HTTPS.** Brings up a TLS terminator that
  obtains and renews its own certificate, moves the dashboard behind it onto
  loopback, and switches the platform to `APP_ENV=production`, where
  `ProductionSafetyValidator` refuses to start on unsafe configuration.
- **`hookflow doctor`** re-runs the machine and configuration checks against an
  existing install, catching a hand-edited `.env` before it becomes an outage.
  It knows the mistakes that actually happen — a shipped default left in a
  secret, `POSTGRES_PASSWORD` drifting from `DB_PASSWORD`.

### Changed
- **One published port.** The dashboard's nginx is the only thing bound to the
  host and proxies every API path to the backend; the API, the actuator,
  Postgres, Kafka and Redis are reachable only inside the Docker network. This
  applies to the development stack too, which previously published five ports —
  so what you learned locally about reaching the API directly stopped working
  the day you deployed.
- **Three Compose files became one, plus a 25-line build overlay.** 384 of
  roughly 470 meaningful lines were duplicated by hand between
  `docker-compose.yml` and `docker-compose.pull.yml`, and they had drifted:
  the worker's actuator bind address differed, leaving Prometheus's scrape
  target unreachable in one deployment path and not the other.
  `docker-compose.prod.yml` is removed — its job was "use images instead of
  building", which is now the default.
- **`MANAGEMENT_ADDRESS` is no longer configurable.** Neither actuator port is
  published, so its only non-default value did nothing but silently break
  Prometheus — twice — and, once nginx became the only way in, the health
  endpoint as well.

### Fixed
- **Registering no longer leaves the dashboard unusable.** With `EMAIL_ENABLED`
  off — the default — nothing could deliver a verification token, but accounts
  were still created `PENDING_VERIFICATION`, and `VerificationGate` disables
  every write in the dashboard for that status. The only way through was to know
  to grep the API container's logs. Verification now runs only when there is an
  email channel to verify over.
- **Malformed requests answered 500.** A wrong HTTP method, unparseable JSON or
  an unsupported content type all fell through to the catch-all handler. For a
  webhook platform that is not a cosmetic wrong number: 5xx means "retry", so a
  sender posting bad JSON was told to keep posting it against a healthy API.
  They are 405, 400 and 415 now, and the 405 carries an `Allow` header.
- **Prometheus could not scrape the API.** The management port exists so metrics
  can be read without a JWT, and three separate comments said so, but Boot copies
  the parent context's filters into the management child context — so
  `/actuator/prometheus` answered 401 on the one port that exists for it to
  answer on.
- **`api` and `worker` did not depend on `postgres`**, so Flyway raced a database
  that had not started accepting connections.
- **Compose overrode the images' JVM tuning with an empty string**, so every
  default deployment ran at `MaxRAMPercentage=25` — roughly 192 MB of heap inside
  a 768 MB limit instead of the intended 576 MB.
- **`VITE_API_URL` and `VITE_CSP_EXTRA_CONNECT` were dead config**, passed as
  runtime environment to an nginx container when Vite inlines them at build time.
- **nginx proxied `/actuator/*` to the wrong port**, so the health path it
  advertised returned 404. Metrics are no longer proxied at all: nginx is the
  public face, and they leak endpoint names and tenant cardinality.

## [2.5.0] - 2026-08-23

Also written after the fact. The release rebuilt the dashboard's design system and
information architecture, generated the API reference from `openapi.yaml` rather
than maintaining it by hand, added Connection to the domain model, gave every
admin screen a loading, empty and error state, and fixed all three SDKs against
the real API.

## [2.4.0] - 2026-08-23

### Changed
- Event intake decides before it writes. `IntakePlanner` is a pure function turning the
  matching Subscriptions, the rules that fired and the project's fanout entitlement into an
  `IntakePlan`; `EventIngestService` then carries that plan out. The routing rules — a DROP
  short-circuiting everything, a rule ROUTE to an endpoint a Subscription already covers not
  being a second Delivery, a rule TRANSFORM overriding the Subscription's own, the fanout
  limit counting both sources after deduplication — were previously interleaved with their
  own writes across ~180 lines and fifteen collaborators, so asserting any of them meant
  standing up Postgres, Kafka and Redis. None of them had a test; all of them do now.

### Added
- **A handler now says who may call it.** `@RequireAccess(AccessLevel)` declares the level a
  state-changing handler requires and `ScopeEnforcementInterceptor` enforces it before the
  handler runs, for JWT and API-key callers alike. 79 handlers carry it (72 `WRITE`,
  7 `OWNER`), derived from the imperative `auth.requireWriteAccess()` /
  `requireOwnerAccess()` calls they already made — which stay, as defence in depth.

  The level is `READ | WRITE | OWNER` rather than a minimum `MembershipRole`, because the
  roles are not a line: `OWNER`, `DEVELOPER` and `VIEWER` order naturally but `API_KEY` sits
  outside that order entirely, and a minimum-role annotation would have had to invent a
  position for it.

  Three handlers once shipped reachable by a `VIEWER` JWT and a `READ_ONLY` API key — one
  returned a real HMAC signature computed with an Endpoint's signing secret — because the
  guard was a call somebody had not written and nothing said it was missing.
  `MutatingHandlerAccessDeclarationTest` now fails the build on a new handler that declares
  nothing, and `AccessLevelEnforcementTest` drives the interceptor directly so a ratchet over
  annotations cannot pass while the thing reading them is unregistered or reordered. See
  ADR-0006.

### Removed
- The worker's `IncomingSource` entity and `IncomingSourceRepository`. Neither was injected
  anywhere in the worker — the Forward path resolves a Destination directly and never loads
  a Source — and keeping them meant keeping a half-mapped secret: the worker mapped the
  Source's encrypted HMAC secret without the key version it was encrypted under, so the
  first worker-side `decryptWithFallback` for a Source would have used the wrong one.
  `incoming_sources` is no longer a shared table.

### Changed
- The api coverage floors were re-measured and raised. BUNDLE 0.30 → **0.37** against a
  measured 40.2% (was 33.1%), and `SequenceGeneratorService` joins `OutboxPublisherService`
  in the CLASS rule at **0.70** — it was deliberately left out at 6.8% with a note to add it
  "once the class is actually tested", and it now measures 77.8%.

### Added
- **Forwards can now be given up on.** `StaleForwardEscalationService` escalates an Incoming
  Forward outstanding past `FORWARD_ESCALATION_HARD_CAP_HOURS` (default 24h) to DLQ,
  mirroring what `StaleDeliveryEscalationService` does for Outgoing. Until now the Incoming
  direction had only a stuck-PROCESSING reset and never wrote a terminal state for a Forward
  whose Destination simply stayed unreachable. The age is measured from when the webhook
  arrived, not from the newest attempt row — Incoming inserts a row per Attempt, so that row
  is freshly stamped even for a Forward that has been retrying since yesterday.
- A Forward that exhausts its Retry Ladder, or is escalated, now publishes a DLQ notification
  to `incoming.forward.dlq`. That topic existed and was created by the Makefile, but nothing
  ever produced a business notification to it.

### Fixed
- **`docker-compose.yml` and `docker-compose.pull.yml` defaulted
  `DELIVERY_ESCALATION_HARD_CAP_HOURS` to 48, which prevents the worker from starting.** The
  outgoing retry ladder's worst case with full jitter is 83h and `RetrySchedulerService`
  refuses to boot when it does not fit inside the cap, so any deployment that did not
  override the variable failed at startup. The 48 predated the ladder gaining its 24h tier.
  Both files now default to 96, matching `.env.dist` and `application.yml`. **If your own
  `.env` still sets 48, change it to 96 — it is not managed by this repository.**
- **A rolled-back ingest no longer consumes quota.** The Redis quota counter was
  incremented inside the ingest transaction, and it is not transactional, so an ingest
  that saved its Event and then aborted — a fanout limit, a downstream failure — kept
  whatever it had added. The customer was charged for an event that does not exist. The
  charge now happens after the commit, the way sequence numbers already did, and a Redis
  outage can no longer fail an ingest the caller has already been told was accepted.

### Changed
- `SsrfProtectionCustomizer` lives once, in `webhook-platform-common`, next to the
  `UrlValidator` it validates against. It had been byte-identical in the api and the worker
  apart from its package line, so an SSRF fix had to be applied in two places and nothing
  said so. Reactor Netty is a `provided` dependency of common on purpose: the api and worker
  already have it through webflux, and the CLI depends on common too and ships as a
  standalone binary with no netty in it — verified unchanged at 0 netty classes.
- **Both delivery pipelines now run one shared attempt lifecycle.** The Incoming forward
  pipeline had been created by copying the Outgoing one, and commit `2070d30` had to
  hand-port four separate fixes from one to the other — landing in the HTTP send, the
  finalisation, the retry scheduler and the Kafka consumer, because the duplication was of
  the whole lifecycle rather than of one method. `AttemptRunner` now owns the order of
  operations and the fences; each direction supplies an `AttemptStore` adapter for how it
  records Attempts. `WebhookDeliveryService` went from 922 lines to 263 and
  `IncomingForwardService` from 760 to 275.
- A Delivery whose URL the platform is not allowed to send to no longer spends a
  concurrency permit and a rate-limit token on being rejected: URL validation moved ahead
  of admission. The permit accounting for failures that happen after admission — a
  decryption failure on a rotated key, a bad client certificate — is unchanged.
- "Endpoint deleted / disabled / unverified" and "Event not found" are now written under
  the delivery's fencing token, like every other finalisation, instead of before the row is
  claimed. A Delivery parked behind an outstanding sequence also stops reading the Endpoint
  and Event on every re-poll just to discover it is still blocked.
- Both Kafka consumers share one collaborator for the executor-full decision. Getting it
  wrong stalls a partition until a restart, and it had been wrong on the Incoming side for
  as long as the Outgoing side had it right.

### Added
- **The Incoming direction is now alerted and its DLQ is now visible.** Two independent
  blind spots, both of which meant an Incoming outage could run indefinitely without
  anything paging anyone:
  - `incoming_forward_attempts_total` appeared in no alert rule in any of the three
    rule files, so a destination failing every Forward looked identical to one receiving
    none. `IncomingForwardFailureRateHigh` mirrors the existing `DlqRateHigh` — same
    expression shape, same 10% threshold, same 10m window — in
    `deploy/prometheus/alerts.yml`, `monitoring/prometheus/alerts.yml` and the Helm
    `prometheusrule.yaml`.
  - A Forward that exhausted its Retry Ladder wrote `status = DLQ` on its
    `incoming_forward_attempts` row and nothing else. `DlqMonitoringService` now counts
    that backlog as `incoming_forward_dlq_depth` and watches the
    `incoming.forward.dlq` topic as `incoming_forward_dlq_topic_retained_total`, the
    counterparts of the existing `webhook_dlq_depth` and
    `webhook_dlq_topic_retained_total`. The row count is the actionable one and has an
    alert; the topic gauge is informational, as on the Outgoing side.
- `RetryLadder` and `RetryLadderDefaults` (`webhook-platform-common`): one shared
  implementation of the retry ladder — parsing, tier clamping, jitter, exhaustion,
  and the worst-case fit against the escalation hard cap. The two directions'
  defaults are now declared once, and stay deliberately different: outgoing gets
  `60,300,900,3600,21600,86400` over 7 attempts, incoming `60,300,900,3600,21600`
  over 5.
- Retry ladders are validated when written. `POST`/`PUT` on a subscription or an
  incoming destination now returns `400` for a malformed `retryDelays` or an out
  of range `maxAttempts`, with a message naming the field and the offending tier.
- `SchemaRetryLadderDefaultsTest` fails the build when a Flyway column default for
  `retry_delays` or `max_attempts` drifts from the Java constant it mirrors. SQL
  cannot reference a Java constant, so nothing else kept the two in agreement.

### Changed
- **A malformed retry ladder is no longer silently replaced.** Both pipelines used
  to answer an unparseable `retry_delays` by logging a warning and substituting a
  hardcoded array of their own — and the two arrays did not agree with each other,
  so a typo bought the customer a retry policy that was neither theirs nor
  documented anywhere. Malformed values are now rejected at write time, and a stored
  ladder that still does not parse — only reachable by writing to the column outside
  the api — fails its delivery or forward terminally with `INVALID_RETRY_LADDER`
  before anything is sent, rather than being retried forever on a substituted ladder.
- Both directions share one deferral backoff — the wait applied when an attempt is
  turned away by a rate limit, a concurrency cap or an open circuit breaker rather
  than made. The incoming pipeline had its own copy that shifted to `1<<6` instead
  of `1<<10` and jittered 50%–150% instead of ±25%, so an incoming forward and an
  outgoing delivery turned away by the same kind of limit backed off on visibly
  different curves.
- The startup check that a retry ladder fits inside
  `DELIVERY_ESCALATION_HARD_CAP_HOURS` now covers **both** directions and validates
  the ladders actually handed out, rather than a config value that could drift from
  them. The incoming ladder was never checked at all.
- OpenAPI drift is now checked by `OpenApiDriftIntegrationTest` rather than by
  booting the whole Compose stack with `SWAGGER_ENABLED=true` and diffing with a
  Python script. The check runs in the existing backend integration job, and an
  intentional API change is regenerated with
  `mvn test -pl webhook-platform-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`.
  The `servers` block is now excluded from the comparison: springdoc derives it
  from the request, so it describes where an instance is reachable, not the API.
- Dropped task-tracker ids (`P0-…`/`P1-…`/`P2-…`) and links to the gitignored
  `.claude/features/` directory from code comments and docs. The technical
  rationale stays inline; only the dangling references are gone.

### Removed
- `RETRY_LADDER_DEFAULT_DELAYS_SECONDS` and `RETRY_LADDER_DEFAULT_MAX_ATTEMPTS`.
  They read as though they set the default retry ladder. They never did — the real
  defaults are the Flyway column defaults and the api services that create the
  rows, and all these variables could change was what the startup cap check
  compared against. Lowering one made the check pass while live rows still carried
  the long ladder; raising one failed startup over a ladder nobody used. No action
  is needed on upgrade; see `UPGRADING.md`.
- `scripts/check-openapi-drift.py`, superseded by `OpenApiDriftIntegrationTest`.

### Fixed
- `deploy/prometheus/alerts.yml` declared `groups:` twice at the top level. Prometheus
  rejects a duplicate mapping key, so the whole file failed to load — the
  `hookflow.outbox` group and every rule after it included. The two are now one mapping.

## [2.3.0] - 2026-08-22

### Added
- `deliveries.claim_token` (V055): a fencing token stamped by whichever claim
  moves a delivery to PROCESSING. `markAsSuccess` / `scheduleRetry` /
  `markAsFailed` now write only while the row's token still matches the one
  their attempt was claimed under. Guarding on `status = PROCESSING` alone
  could not tell an attempt's own claim from a newer one: after
  `StuckDeliveryRecoveryService` released a claim and the ladder reclaimed the
  row, the abandoned attempt's late response finalized a delivery it no longer
  owned, and the reclaimed attempt never reached the endpoint at all.
- `ORDERING_BUFFER_RESCHEDULE_DELAY_SECONDS`: the fallback poll interval for a
  delivery parked behind an outstanding sequence, previously hardcoded at 5s.
- `OpenApiOperationIdTest`: fails the build on any controller method that would
  be handed a scan-order-dependent operationId.
- `scripts/check-openapi-drift.py`: semantic (parsed) comparison of the
  committed openapi.yaml against the live spec.
- GitFlow branching strategy with `develop` branch
- CONTRIBUTING.md with development guidelines
- Issue and PR templates
- SECURITY.md policy

### Changed
- OWASP Dependency-Check moved out of CI into `.github/workflows/security-sca.yml`,
  now nightly plus `release/*` and `hotfix/*`, with a 75-minute timeout and its
  NVD cache saved even when the scan fails. It had been costing 60-104 minutes
  per run whenever the cache was cold — which a failed scan guaranteed for the
  next run, since `actions/cache` skips its save step on failure. Pull requests
  keep dependency-CVE coverage through the Trivy image scan.
- `RetryGovernor` poll-interval recommendations are now multiples of the
  configured interval instead of hardcoded constants, so
  `RETRY_SCHEDULER_POLL_INTERVAL_MS` finally takes effect. The multipliers
  reproduce the previous 30s/10s/5s/2s exactly at the 10s default.
- OpenAPI operationIds are deterministic: `OperationIdNamingConfig` replaces
  springdoc's positional `_1`/`_2` disambiguation, and 43 cross-controller
  collisions carry explicit, descriptive ids. The spec is now byte-identical
  across restarts.
- **Spring Boot upgraded 3.2.0 → 3.5.16** (the 3.2.x line went OSS-EOL in
  2024; 3.5.16 was the final OSS release of the 3.5.x line before it too
  went EOL 2026-06-30 - see the comment on `spring-boot.version` in the root
  `pom.xml` for why this stops short of the current Spring Boot 4.x line). Along with it: jjwt 0.12.3 →
  0.13.0, redisson-spring-boot-starter 3.24.3 → 3.52.0, ShedLock 5.10.0 →
  5.16.0, springdoc-openapi 2.3.0 → 2.9.0, stripe-java 28.2.0 → 28.4.0,
  maven-surefire-plugin 2.22.2 → 3.5.6 (required - the old version silently
  discovered zero tests under Boot 3.5.16's newer JUnit Jupiter).
- UI build image `node:18-alpine` (EOL April 2025) → `node:22-alpine`;
  runtime image `nginx:1.25-alpine` → `nginx:1.30-alpine`. Vite 5 → 7,
  Vitest 1 → 3.
- Helm chart (`deploy/helm/hookflow`): removed the Bitnami
  postgresql/redis/kafka subchart dependencies (Bitnami restricted its free
  catalog in August 2025 and dropped Kafka from it entirely). The chart now
  requires bring-your-own PostgreSQL/Kafka/Redis via each service's
  `external.*` values - see the Helm README.

### Fixed
- `RetrySchedulerService` no longer writes back rows whose Kafka send succeeded.
  A successful send hands the row to the consumer, which often advanced it
  within milliseconds; re-saving the Phase 1 snapshot raced that update, and
  when the consumer lost the optimistic-lock race `BoundedAsyncExecutor` did not
  ack — **stalling the entire retry partition until a restart or rebalance**.
- The ordering buffer tolerates a concurrent update while parking a delivery
  instead of failing the consumer task (same partition-stall blast radius).
- Integration tests with proper `@MockBean` for Redis services
- `GlobalExceptionHandler` now properly handles `ResponseStatusException`
- Test assertions in `MembershipRbacTest` and `AuthIntegrationTest`

## [2.2.1] - 2026-03-18

### Fixed
- Small worker-side fix following the CLI module release (`8aba8fa`).

## [2.2.0] - 2026-03-16

A large release spanning several new subsystems, folded into one changelog
entry because the underlying commit history (`add feature` / `add cli
module` / `fix`, ~160 commits) doesn't distinguish them individually. The
Flyway migrations added in this range (`V028`–`V042`) are the most reliable
record of what shipped:

### Added
- **Rules engine** for conditional event routing (`V028_rules_engine`,
  `V029_rules_condition_tree`)
- **Workflow engine**: multi-step workflows with reliability/retry tracking
  (`V030_workflows`, `V031_workflow_reliability`)
- **Billing**: plans, subscriptions, and yearly-interval pricing
  (`V036_billing_plans`, `V037_billing_subscriptions`,
  `V038_billing_yearly_interval`)
- **CLI** (`webhook-platform-cli`) as a standalone Picocli module, published
  via a new `release-cli.yml` workflow
- **Tunnel**: `CLI ↔ /ws/tunnel` local-development tunneling, with session
  tracking, request logging, and plan-based limits (`V040_tunnel_sessions`,
  `V041_tunnel_request_log`, `V042_tunnel_plan_limits`)
- Multi-key encryption support for zero-downtime key rotation
  (`WEBHOOK_ENCRYPTION_KEYS`, `WEBHOOK_ENCRYPTION_KEY_ACTIVE_VERSION`,
  `V039_encryption_key_versioning`) — additive and optional; existing
  single-key deployments are unaffected
- Dashboard materialized view for faster analytics queries
  (`V033_dashboard_materialized_view`)
- Event payload compression (`V032_event_payload_compression`)
- API key scopes (`V025_api_key_scope`)
- PII masking and debug links for delivery inspection
  (`V012_pii_masking_and_debug_links`)
- Replay sessions for re-driving past deliveries (`V013_replay_sessions`,
  `V018_replay_unique_constraint`)

### Changed
- Invite tokens are now hashed at rest rather than stored in plaintext
  (`V034_hash_invite_tokens`)
- Several indexing passes for delivery-dashboard and high-load query paths
  (`V015`, `V019`, `V022`, `V035`)

## [2.1.0] - 2026-03-02

### Added
- Wildcard subscriptions (route by event-type pattern, not just exact match)
- Event schema registry (`V010_schema_registry`)
- Deterministic replay support (`V011_deterministic_replay`)

## [2.0.0] - 2026-03-01

**Major release — breaking changes. See [UPGRADING.md](UPGRADING.md) before
upgrading an existing v1.x deployment.**

### Security
- **Encryption key derivation replaced.** Secrets (endpoint signing
  secrets, source secrets, destination auth) were previously encrypted with
  a key derived by truncating a SHA-256 digest of `WEBHOOK_ENCRYPTION_KEY`
  to 16 bytes (effectively AES-128). This is now `PBKDF2WithHmacSHA256`
  (65,536 iterations) over `WEBHOOK_ENCRYPTION_KEY` + a new required
  `WEBHOOK_ENCRYPTION_SALT`, producing a real 256-bit AES key
  (`CryptoUtils.deriveKey`). **Ciphertext encrypted under v1.x cannot be
  decrypted by v2.x** — see UPGRADING.md.
- Request/payload size limits enforced via a new `RequestSizeLimitFilter`
  (`WEBHOOK_MAX_PAYLOAD_SIZE_BYTES`, `WEBHOOK_INCOMING_MAX_PAYLOAD_SIZE_BYTES`)
- Auth rate limiting on login/register, independent of the general API rate
  limiter (`AUTH_RATE_LIMIT_LOGIN_PER_MINUTE`, `AUTH_RATE_LIMIT_REGISTER_PER_MINUTE`)
- Refresh-token handling hardened; typed exceptions replace generic ones in
  several security-sensitive paths
- Redis now requires authentication (`REDIS_PASSWORD`, defaulted in
  `docker-compose.yml` but must be set explicitly in production)
- Kafka, Redis, and API ports are no longer published on all interfaces by
  default — Kafka/Redis bind to `127.0.0.1`, and the API respects a new
  `API_BIND` variable (default `127.0.0.1`, was implicitly `0.0.0.0`)
- Membership invite tokens now expire and are tracked server-side
  (`V008_membership_invite_tokens`)
- Outbox publisher tracks `last_attempt_at` to prevent silently stuck
  messages from being re-picked forever (`V009_outbox_last_attempt_at`)
- Webhook signature verification enforcement tightened in
  `WebhookVerifierFactory`
- `ProductionSafetyValidator` added — fails startup on unsafe production
  config (default secrets, `WEBHOOK_ALLOW_PRIVATE_IPS=true` in prod, etc.)

### Added
- Incoming webhooks (ingress) pipeline: source/destination management,
  request forwarding, retry scheduling
  (`V005_incoming_webhooks`, `V006_incoming_webhooks_highload`,
  `V007_incoming_webhooks_enhancements`)
- Redis-distributed rate limiting and reactive delivery path; Kafka topics
  moved to 12 partitions for higher throughput
- DLQ management, payload transformation, custom headers, and IP allowlist
  for outgoing endpoints
- OpenAPI docs, request DTO validation, rate-limit response headers, and a
  delivery circuit breaker
- mTLS support for outbound webhook delivery
- Endpoint ownership verification flow
- PHP SDK (`sdks/php`), alongside the existing Node and Python SDKs
- Email service for verification and password-reset mail
  (`V003_email_verification`, `V004_password_reset`), with `EMAIL_ENABLED`,
  `SMTP_*` env vars (SMTP disabled by default — verification links are
  logged to console)
- Audit log (`V002_audit_log`)
- UI internationalization: English and Ukrainian locales
- Resource limits, log rotation, and healthcheck tuning across all
  `docker-compose.yml` services

### Changed
- **Schema history replaced.** All pre-2.0 Flyway migrations
  (`V001`–`V025` under the old numbering) were consolidated into a new
  `V001__initial_schema.sql`…`V009__outbox_last_attempt_at.sql` set. This is
  a fresh baseline, not a continuation — see UPGRADING.md for what this
  means for an existing v1.x database.
- `docker-compose.yml` no longer sets explicit `container_name` on the
  `api`/`worker`/`ui` services; the default `TEST_ENDPOINT_BASE_URL`
  changed from `http://webhook-api:8080` to `http://api:8080` to match
  (Docker Compose's built-in service-name DNS, not the removed container
  name)
- Vendored PHP SDK dependencies (`sdks/php/vendor/`) removed from version
  control — run `composer install` locally instead

## [1.1.0] - 2026-02-16

*Tagging anomaly: this tag is an ancestor of `v1.0.1`–`v1.0.3` below — those
three patch releases were cut from the `1.1.0` line but kept the `1.0.x`
numbering rather than `1.1.x`. Listed here in the chronological order the
releases actually happened, not strict numeric order.*

### Added
- DLQ management, payload transformation, custom headers, and IP allowlist
  for outgoing endpoints
- OpenAPI documentation, request DTO validation, rate-limit response
  headers, delivery circuit breaker
- PHP client SDK
- Redis-distributed rate limiting, reactive delivery path, 12 Kafka
  partitions for higher throughput (`feat(highload)`)
- JVM tuning for the API/worker containers

### Fixed
- CI: Testcontainers/Docker compatibility fixes for integration tests
  (Docker API version pinning, container pre-pull, socket permissions)
- Various integration-test stability fixes (Redis/Kafka mocking, ordering
  fields, `ResponseStatusException` handling)

## [1.0.1] - 2026-02-18

- First publish of the Node.js, Python, and PHP SDKs to npm/PyPI/Packagist,
  with a dedicated CI workflow (`publish-sdks.yml`)

## [1.0.2] - 2026-02-18

- Fixed PHPUnit configuration in the PHP SDK's CI job

## [1.0.3] - 2026-02-18

- Fixed PHP SDK CI (`--no-coverage` flag) and corrected author metadata in
  package manifests

## [1.0.0] - 2025-12-17

### Added
- **Core Platform**
  - Multi-tenant webhook management with organization isolation
  - Event ingestion API with payload validation
  - Subscription management for routing events to endpoints

- **Delivery Engine**
  - Reliable webhook delivery with exponential backoff retry
  - HMAC-SHA256 signature generation for payload verification
  - Configurable retry policies (max attempts, backoff multiplier)
  - Dead letter queue for failed deliveries

- **High Availability**
  - Redis-based distributed rate limiting
  - ShedLock for distributed scheduler coordination
  - Kafka-based event streaming between API and Worker

- **Security**
  - JWT authentication with refresh tokens
  - API key authentication for programmatic access
  - Role-based access control (Owner, Admin, Developer, Viewer)

- **Observability**
  - Real-time delivery dashboard
  - Delivery attempt history and logs
  - Event and subscription analytics

- **Infrastructure**
  - Docker Compose setup for local development
  - Kubernetes-ready with health checks
  - PostgreSQL for persistent storage
  - Redis for caching and rate limiting
  - Kafka for event streaming

### Technical Stack
- Backend: Java 17, Spring Boot 3.x
- Frontend: React 18, TypeScript, Vite, TailwindCSS
- Database: PostgreSQL 15
- Cache: Redis 7
- Message Broker: Apache Kafka

[Unreleased]: https://github.com/vadymkykalo/webhook-platform/compare/v2.10.0...HEAD
[2.10.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.9.1...v2.10.0
[2.9.1]: https://github.com/vadymkykalo/webhook-platform/compare/v2.9.0...v2.9.1
[2.9.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.8.0...v2.9.0
[2.8.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.7.0...v2.8.0
[2.7.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.6.1...v2.7.0
[2.6.1]: https://github.com/vadymkykalo/webhook-platform/compare/v2.6.0...v2.6.1
[2.6.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.5.0...v2.6.0
[2.5.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.4.0...v2.5.0
[2.4.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.2.1...v2.3.0
[2.2.1]: https://github.com/vadymkykalo/webhook-platform/compare/v2.2.0...v2.2.1
[2.2.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/vadymkykalo/webhook-platform/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/vadymkykalo/webhook-platform/compare/v1.0.3...v2.0.0
[1.1.0]: https://github.com/vadymkykalo/webhook-platform/compare/v1.0.0...v1.1.0
[1.0.1]: https://github.com/vadymkykalo/webhook-platform/compare/v1.1.0...v1.0.1
[1.0.2]: https://github.com/vadymkykalo/webhook-platform/compare/v1.0.1...v1.0.2
[1.0.3]: https://github.com/vadymkykalo/webhook-platform/compare/v1.0.2...v1.0.3
[1.0.0]: https://github.com/vadymkykalo/webhook-platform/releases/tag/v1.0.0

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Blog: "What is a webhook? How webhooks work, and the six ways they break in production"** — a
  beginner-to-intermediate guide: webhooks against API polling, a real signed Standard Webhooks
  request, verification on the raw body in Node and Python, acknowledging fast, deduplicating and
  ordering, then downtime, timeouts, duplicates, out-of-order delivery, signature and rotation
  mistakes and silent drops, each with its fix. Seven new diagrams; Ukrainian translation
  alongside.
- **Blog: "The transactional outbox, or how Railhook never loses an event it has accepted"** —
  at-most-once, at-least-once and exactly-once over HTTP; the dual write and how the outbox closes
  it; how the publisher, the Claim and its fence, the Attempt Runner's invariants and the retry
  ladders absorb every duplicate but the one a receiver dedupes on `webhook-id`; and what a polling
  publisher leaves open compared with CDC. Four new diagrams, in English and Ukrainian.
- **Article: "How to build software with an AI coding agent that you can actually trust"** —
  lessons from building Railhook by directing an AI agent, each drawn from a real incident: where
  the agent was confidently wrong, why written rules are not enough, and the checks that now refuse
  each class of bug. Two figures, and a Ukrainian translation alongside.

- **A live demo, "Try the live demo"**, on the landing page's hero, on `/pricing` and in the
  Developers menu: `/demo` opens the real dashboard signed in to a sample organization — project
  "Acme Shop" with four endpoints, successful and retried deliveries with their attempts, a
  Failed Messages entry, Stripe and GitHub sources with verified incoming events, and analytics
  for the last 24 hours. Off unless `DEMO_ENABLED=true` (with `DEMO_SESSION_TTL_MINUTES` and
  `DEMO_REFRESH_INTERVAL_MINUTES`); a self-hosted install creates no demo organization, shows no
  entry point and answers `404` on `POST /api/v1/public/demo/session`.
- **The demo is read-only on the server, not only in the dashboard.** A demo session is a
  30-minute Viewer access token with no refresh token, accepted only under `/api/`, and every
  request from it other than `GET`, `HEAD` or `OPTIONS` is refused with `403 demo_read_only` —
  including the handlers no role guards (password, email change, members, device and MCP
  approval, the portal) — as are exports. Any credential for the demo organization is treated
  the same way. Ten sessions a minute per address, behind the registration CAPTCHA where one is
  configured. An integration test walks every state-changing handler the running API has; a
  ratchet freezes the four that a demo session may call.
- The demo's data is regenerated every hour so it always ends at the present, on reserved
  `.example` hosts, with every delivery already finished and its sources refusing webhooks, so
  the worker never has anything of it to send. The platform admin overview and the worker's DLQ
  gauges (and so the `webhook_dlq_depth` alert) leave it out.
- Docs: **Self-hosting → Live demo**, how to turn it on and why it is off, in English and
  Ukrainian.
- **Syntax highlighting like an IDE on the public site.** Blog code blocks are coloured by kind
  (keywords, strings, numbers and constants, comments, types and generics, Java `@Annotations`,
  function calls, punctuation) in Java, JavaScript/TypeScript, Python, SQL, YAML, JSON, HTTP and
  shell; an unknown language stays plain text. The install command and the tester's `curl` are
  coloured too. It is the site's own scanner — no highlighting library, nothing compiled at
  runtime, so the CSP is unchanged — about 1 kB gzipped more, only on the pages that show code.
  The palette grows from the brand cobalt, avoids every status colour and clears WCAG AA in both
  themes.

### Changed

- **Blog figures are easier to read**: every label and note is set 2px larger, secondary text is
  a darker grey, and the figures that crowded were re-laid out. "Anatomy of a webhook request" is
  set larger still, with its five callouts spaced apart and their descriptions wrapped.
- **Blog byline, tags and the "sources checked" note are larger**, and a whole post card on
  `/blog` is now the link, with a focus ring around the card.

### Fixed

- `npm run blog:og` drew only the first post's social card: the second timed out on the reused
  browser page. Each card now gets a page of its own.
- Two blog headings that reduce to the same anchor (common in Ukrainian, where only Latin words
  survive) no longer share one; the second is numbered, so the contents link to both.

## [2.25.0] - 2026-09-19

### Added

- **A blog**, at `/blog` and `/blog/<slug>`: prerendered, indexed, in English and Ukrainian, with
  an RSS feed at `/blog/rss.xml`. Posts are Markdown files under
  `railhook-ui/src/content/blog/<slug>/`, one per language, with typed front matter; the route
  list, the sitemap and the feed all enumerate that directory, so adding a post is adding a
  directory. Each article carries a table of contents on a wide screen, hand-drawn SVG diagrams
  that work on paper and on ink, a per-article social card under `public/blog/`, `Article` and
  `BreadcrumbList` structured data, and the date its external claims were last checked.
  `railhook-ui/src/content/blog/README.md` is the authoring contract.
- **First article: "What Stripe, GitHub and Shopify actually do when your endpoint is down"** —
  each provider's timeout, retry schedule and give-up behaviour, quoted from and linked to their
  own documentation, with what a gateway in front of them changes. Ukrainian translation
  alongside.

### Changed

- **The language switch moved from the footer into the header**, where a reader who cannot read
  the page does not have to scroll past all of it to say so; on a phone it is inside the menu.
  The theme toggle stays in the footer. The header also gains a Blog link.
- `useDocumentMeta` now writes `og:image` and `twitter:image` on every page — a page with no card
  of its own restores the site's, so an article's card no longer follows the reader to the next
  page.

### Fixed
- **The message widget could not be closed on a phone.** It opened as a small panel pinned to the
  launcher, and with the keyboard up both its close button and the launcher were off screen. On a
  phone it is now a full-screen sheet above the header, with the close button always in view and
  the page behind it locked. Worse, while closed the panel still covered a phone screen
  invisibly — a responsive `display` class beat the `hidden` attribute — and swallowed every tap
  on the page behind it.
- **The cookie notice's second button overflowed its border** on a phone, where the Ukrainian
  "Політика конфіденційності" does not fit. The policy is now a link in the sentence and the
  notice has one button.

- **The nightly ordering probe measured other scenarios' traffic.** One load-receiver serves every
  scenario in a run and a retry ladder outlives the scenario that started it, so deliveries still
  draining from the failure-recovery run were counted as the ordering burst — 135 "out-of-order
  transitions" across sequence numbers the probe never sent. Each scenario now subscribes its own
  receiver path and asks for a summary of that path alone, a forced failure is bound to the path
  that asked for it, and a run in which nothing arrived fails instead of passing silently. FIFO
  ordering itself was never broken: rerun locally against the same sequence, 0 out-of-order
  transitions and 0 duplicates.

## [2.24.0] - 2026-09-19

### Added

- **"See it in action" on the landing page**, straight after the hero: six screenshots of the
  real product — deliveries, one delivery's attempts, failed messages with replay, incoming
  Stripe and GitHub webhooks, the embedded customer portal and analytics — behind tabs that the
  arrow keys move between, each with a one-line caption. Every screen is captured in both
  themes and follows the site's; on a phone the tabs scroll sideways and the page does not. It
  replaces the three-screenshot section further down, and the README shows three of the new
  captures.

### Changed

- **The header gains a Developers menu and an About link.** Docs, the CLI, the MCP server, the
  webhook tester, the signature verifier and the status page sit behind one menu, each with a
  line on what it is. The footer links to the CLI next to the MCP server.

## [2.23.0] - 2026-09-18

### Added

- **Onboarding email for new accounts.** With `ONBOARDING_EMAILS_ENABLED=true`, an account gets a
  short welcome once its address is verified (or at sign-up, when it is created already verified)
  with three next steps — the quickstart, receiving from Stripe or GitHub, and replying with
  questions — and, two days later, one nudge if its organization has still sent and received no
  events. Each is sent at most once per account, replies go to `EMAIL_SUPPORT_ADDRESS`, and
  suspended accounts and organizations are skipped. Off by default: both mails are written as
  Railhook's author, for Railhook Cloud. Accounts that exist before the upgrade are marked as
  already onboarded and never receive either. Migration `V082` adds two nullable columns to
  `users`.
- **Webhook signature verifier** at `/tools/webhook-signature`: paste a body, a secret and the
  signature header to see whether they match, and what the signature should have been — for
  Standard Webhooks, Stripe, GitHub, Shopify, Slack and Railhook's own `X-Signature`, with a
  warning when a timestamp is outside the 5-minute window. It runs in the browser with Web
  Crypto; nothing pasted into it is sent anywhere.
- **Security, About and Changelog pages** on the public site. `/security` states how data is
  hosted, how secrets, API keys and passwords are stored, how organizations are isolated and
  how to report a vulnerability. `/about` says who builds Railhook and on what. `/changelog` is
  built from this file on every build, newest release first, each at its own anchor.
- The footer gains a Company column (About, Security, Changelog, Contact, Privacy, Terms) and a
  link to the signature verifier.

- **Status page.** `deploy/status-page/` holds the Cloudflare Worker behind status.railhook.io:
  it probes the site, the API, the dashboard, the docs and the MCP server once a minute, keeps
  90 days of uptime in D1, and opens and resolves incidents on its own. `STATUS_PAGE_URL` puts a
  Status link in the site's footer; empty, the default, shows none.

### Changed

- The contact form answers at once: the mail to support is sent in the background.
- **The contact form has a daily ceiling across all senders**, `CONTACT_DAILY_LIMIT` (30 by
  default), so a flood from many addresses cannot spend the mail quota that verification and
  password-reset mails need. Onboarding nudges go at most ten an hour for the same reason.

## [2.22.0] - 2026-09-18

### Added

- **Write to support from any public page.** A "Message us" widget in the corner of the public
  site, and the same form on the contact page, send the message to the deployment's support
  address (`EMAIL_SUPPORT_ADDRESS`) with the visitor's email as Reply-To. It never mails the
  address the visitor typed, sits behind the CAPTCHA where one is configured, and allows two
  messages a minute per address. It is shown only where `RAILHOOK_CONTACT_DOMAIN` is set.
- **Cookie notice.** Where web analytics is configured, the public site says once what it stores:
  the sign-in cookie, and visit counts from Cloudflare Web Analytics, which sets no cookies. The
  privacy policy gains a section on cookies, analytics and messages.

### Changed

- The contact page no longer calls paid support "future".

## [2.21.2] - 2026-09-18

### Changed

- **The footer links to the MCP server docs.**

## [2.21.1] - 2026-09-18

### Changed

- **The header links to Pricing**, in place of a "Cloud" link that went to the same section as
  "Self-host".

## [2.21.0] - 2026-09-18

### Added

- **MCP server for AI agents.** Railhook serves a remote MCP server at `/mcp` (Streamable HTTP,
  authenticated with a project API key as a bearer token or `X-API-Key`), so Claude Code, Cursor
  and other agents can send events, list endpoints, subscriptions and deliveries, read a
  delivery's attempts, create endpoints and subscriptions, and replay a delivery. A `READ_ONLY`
  key gets the read tools only. `@railhook/mcp` on npm bridges it to stdio-only clients such as
  Claude Desktop. `MCP_ENABLED=false` turns it off on a self-hosted instance.
- **claude.ai and ChatGPT connect to the MCP server by signing in.** OAuth 2.1 with PKCE, dynamic
  client registration and protected-resource metadata: add `https://<your host>/mcp` as a custom
  connector, sign in, pick a project and read-only or read-write. Connected apps are listed next
  to API keys and can be disconnected there. `MCP_OAUTH_ENABLED=false` turns it off.
- **Customer portal.** Group endpoints by Consumer — one of your own users, keyed by your own
  id — and open a short-lived portal session for them from your backend. The portal embeds in
  your product in an iframe: your users register endpoints, choose event types, see every
  delivery with its attempts, and retry failed ones, with your brand colour, logo, theme and
  language. A session can be pinned to the one origin allowed to embed it. SDK methods in
  Node.js, Python and PHP.
- **Free webhook tester** at `/tester` on the public site: a URL that records the requests sent
  to it for a day, no account needed. Off unless `PUBLIC_TESTER_ENABLED=true`; bounded per
  address, per URL and overall, and it asks for the registration CAPTCHA where one is set.
- **`/pricing`** — the free cloud plan and the self-hosted promise, with an FAQ.
- **Web analytics** — Cloudflare Web Analytics on the public pages, the dashboard and the docs
  when `WEB_ANALYTICS_TOKEN` is set; nothing is loaded otherwise.
- **Platform admin** — activation over the last 30 days and sign-ups and events per day.
- **Docs** — comparisons with Svix, Hookdeck, Convoy and Hook0, and guides for receiving
  Stripe, GitHub, GitLab, Shopify, Slack and Twilio webhooks.

## [2.20.13] - 2026-09-18

### Fixed

- **Tunnels relay bodies byte for byte.** A form body (Slack slash commands, Twilio callbacks)
  was rebuilt from its parsed fields and binary bodies were re-encoded, so the app behind
  `railhook tunnel` rejected the provider's signature. Requests and responses now arrive exactly
  as sent; binary bodies need the updated CLI, text and form bodies are fixed for every CLI.
  Test captures (`/hook`) store a form body as it was sent.
- **Two deliveries with the same body are no longer refused as a replay** when the provider
  gives each its own delivery id.
- **A repeated dispatch message no longer runs a delivery's next attempt early**, ahead of its
  retry backoff.
- **Paid checkout** (not yet enabled on railhook.io): starting a checkout creates the
  subscription, the first successful payment moves the organization to the plan, renewals charge
  the amount the checkout charged, and WayForPay callbacks for prices with kopecks verify.

## [2.20.12] - 2026-09-18

### Security

- **Inviting an address no longer hands the organization to an account that never proved it
  owns that address.** An account could be registered for someone else's address and left
  unverified; when an owner later invited that address, the account became a member and could
  read the organization's events. With email delivery on, inviting an address whose account has
  not verified it is now refused, with a message saying to ask them to verify first.

### Fixed

- **Ordered events sent to `POST /events` are delivered in order.** Their sequence number was
  never written, so ordering was silently not enforced for them; the sweep meant to repair that
  failed the same way.
- **Slack apps can be connected to a Slack source.** The ingress now answers Slack's
  `url_verification` handshake with the challenge (after checking the signature), instead of
  `202`, which Slack refused.
- **Forwards carry the provider's event headers** — `X-GitHub-Event`, `X-Gitlab-Event`,
  `X-Shopify-Topic` and related ones — so a destination can tell one event type from another.
  Signatures and tokens are still never forwarded.
- **Stripe and Twilio resends are deduplicated.** Railhook looked for id headers these providers
  don't send; it now uses Stripe's event `id` from the body and Twilio's
  `I-Twilio-Idempotency-Token`.
- **Someone removed from their only organization can sign in again**, into an organization of
  their own, instead of getting `404` on every sign-in.
- **A manually retried delivery no longer waits up to an hour** when the worker is busy.
- **Monitoring:** the API error-rate alert no longer counts tunnel traffic (a customer's own
  server answering through a tunnel) and reports the rate correctly; an upgrade now restarts the
  monitoring stack onto the new rules instead of leaving it on the ones it started with.

## [2.20.11] - 2026-09-18

### Fixed

- **Verifying an endpoint behind a tunnel that isn't running says so.** The error read
  `503 Service Unavailable from POST https://…/tunnel/…`; it now says the tunnel isn't connected
  and to start `railhook tunnel`. The verify response carries `reason: TUNNEL_OFFLINE` for API
  clients.

## [2.20.10] - 2026-09-18

### Fixed

- **Test events reach pattern subscriptions.** Sending a test event from the Test Console matched
  subscriptions by exact event type and skipped the rules, so an `order.*` subscription got no
  delivery from a test `order.completed` while the same event sent through the API was delivered.
  A test event now goes to the deliveries a real one would: pattern subscriptions, rules and the
  fan-out limit included.
- **Retrying an accepted event with its `Idempotency-Key` returns that event once the monthly
  quota is used up.** The quota was checked before the key was looked up, so a client that lost the
  response to the event which used the month's last slot, and retried it, got `402` for an event
  already accepted. A new event over the quota is still refused.

## [2.20.9] - 2026-09-17

### Security

- **An API key reaches only its own project.** A key for one project could read, change or delete
  another project's endpoints, subscriptions, rules, transformations, schemas, workflows, incoming
  sources and destinations in the same organization through its own project's URLs, including
  rotating another project's endpoint secret and getting the new secret back. Such a request now
  answers `404`, and a workflow can no longer point at another project's endpoint.
- **A suspended member can no longer switch back into the organization.** Signing in to another
  organization and switching back issued a fresh token for the suspended membership.
- **Deleting a project stops it.** Its API keys are revoked and refused, and its events, incoming
  webhooks and test captures are refused, instead of continuing to run while no longer counting
  towards the plan. Deliveries and forwards already queued for it are no longer sent: they end as
  failed.
- **A suspended organization stops receiving.** `/ingress` answers `403` (not `410`, so providers
  keep their subscriptions for when the suspension is lifted), and test captures and tunnels are
  refused. Its queued deliveries and forwards are held, and sent once the suspension is lifted.
- **API keys can no longer create, rotate or revoke API keys.** Managing keys needs a signed-in
  user, so a leaked key cannot mint replacements that outlive its revocation.
- **Reusing a rotated refresh token ends every session**, not just the access tokens.
- **Owner and API-key roles cannot be granted through the member endpoints.**
- **mTLS clients are built separately per endpoint.** Two endpoints' clients built at the same
  moment could share one endpoint's client certificate.
- **WayForPay callbacks are bound to their signed order reference and deduplicated**, so a
  replayed or altered callback cannot renew or mark past due a subscription it does not belong to.
- **A closed tunnel is disconnected** across every instance, and a tunnel response is accepted
  only from the tunnel's own connection. A member's tunnels close when they are removed or
  suspended, an organization's when it is deleted, and a person's everywhere when their account is
  erased.

### Changed

- **Plan limits hold under concurrent creates** for projects, endpoints and members, and an
  incoming source's rate limit is capped at the plan's.
- **A retry ladder longer than the escalation cap is refused** (96 hours outgoing, 24 hours
  incoming) instead of being moved to Failed Messages before its later retries run.
- **Failed Messages lost its search and date filters**, which the API never applied: a "select
  all" after filtering acted on every failed message.

### Fixed

- **Fewer duplicate deliveries.** A delivery handed back when the worker was saturated, an incoming
  retry whose send landed late, a Kafka acknowledgement arriving after the outbox stopped waiting,
  and a concurrent sweep during an incoming forward's completion could each send a webhook twice.
- **One malformed Kafka record no longer stops a consumer.** It goes to the dead-letter topic
  byte for byte, and a record sent there no longer stalls the rest of its partition.
- **Rate limits and concurrency limits take effect when changed**, recover when Redis evicts or
  loses their keys, and the global limit no longer falls back to per-instance after 24 hours.
- **Ordered endpoints keep delivering while Redis is unavailable**, and their buffer no longer
  grows without bound.
- **Replays interrupted by a restart are marked failed** instead of staying running and holding
  the project's replay slots, and a replay is no longer run on the request thread when the pool is
  busy.
- **Retention no longer scans every delivery attempt per batch**, and every retention loop stops
  before its lock expires.
- **Incoming events with forwards still in progress are kept by retention.**
- **A uniqueness conflict answers `409` and an unknown sort property `400`**, instead of `500`.
- **A token issued in the same second as a password change is no longer rejected.**
- **The dashboard:**
  - Several tabs refreshing a session at once no longer sign the person out everywhere.
  - Requests waiting on a failed session refresh fail instead of spinning forever.
  - Signing out clears everything cached for that person.
  - Every event shows its exact delivery status, on any page and however many endpoints it fans
    out to.
  - Invite and CLI sign-in links survive signing in or registering first.
  - A changed role or organization is picked up without signing out.
  - Lists keep their rows while the next page loads, and the delivery panel no longer closes
    every minute.
  - Each failed action shows one error toast, not two.
  - Large integers in JSON are no longer rounded when formatted.
  - Imported endpoints keep their signature scheme and show their new secrets.
  - A page opened across a deploy reloads instead of showing a blank error.
  - The test console no longer mixes results of earlier sends.
  - Enabling a workflow keeps unsaved canvas edits.

## [2.20.8] - 2026-09-15

### Changed

- **Time Machine replays apply the project's rules as they are now.** A replayed event goes
  exactly where a fresh ingest of it would: a replay filtered by event type also reaches pattern
  subscriptions such as `order.*`, a `DROP` rule replays nothing, a `ROUTE` rule adds its
  endpoint, and a `TRANSFORM` rule replaces the subscription's transformation. An event over the
  fan-out limit is replayed nowhere and counts as a session error. The estimate counts pattern
  subscriptions but does not apply rules.
- **A destination or endpoint answering 3xx or 4xx lands in Failed Messages.** Both outgoing
  deliveries and incoming forwards used to end as failed, where nothing offers a retry, so a 401
  after a token rotation or a 404 during a deploy could only be recovered by a replay. They now go
  straight to Failed Messages without using the rest of the retry ladder, ready to retry once the
  credentials or URL are fixed, and dead-letter alerts count them.

### Fixed

- **An upgrade no longer crashes the worker on a new migration.** `./railhook upgrade` replaces
  the worker only after the new API has migrated. A worker started beside an API that is still
  migrating (a fresh install, `./railhook start` after a tag change, a Helm upgrade) waits up to
  15 minutes instead of exiting on `Schema validation: missing column`.
- **One busy retry no longer holds up the others for five minutes.** When a retry the worker had
  given up waiting on was picked up anyway, the other retries scheduled with it could stay in
  progress until the stuck-delivery sweep; they are now rescheduled straight away.
- **Delivery attempts show every header that was sent.** The request headers recorded for an
  outgoing attempt now include `X-Sequence-Number`, `Idempotency-Key` and your custom headers
  (secrets masked), as they already appeared on the wire.
- **Forwards carry exactly what the provider sent.** Bodies that are not UTF-8 (other charsets,
  binary, gzip) reach the destination byte for byte with their `Content-Encoding`, and a body
  containing a NUL byte is accepted instead of answered `500`.
- **A GitLab resend is no longer forwarded twice.** Railhook reads GitLab's delivery id
  (`webhook-id`, or `Idempotency-Key` from GitLab 17.4), so a retry or a manual Resend is answered
  with the stored event.
- **A dropped tunnel fails its requests at once, and the tunnel limit holds.** A request in flight
  when the CLI disconnects is answered `502` immediately instead of `504` after 30 seconds, a quick
  reconnect keeps its tunnel, and concurrent opens can no longer exceed the plan's active-tunnel
  limit.
- **An over-quota ingress refusal says when to retry.** The `429` for an organization that cannot
  accept more webhooks now carries `Retry-After: 3600`.
- **A Redis hiccup no longer rejects verified webhooks.** When replay detection cannot reach Redis,
  a webhook whose signature verified is accepted instead of answered `500`, and
  `incoming_replay_check_unavailable_total` counts each one.
- **Daily usage counts include deliveries that were still retrying at midnight.** Each night the
  last five days are recounted until their deliveries settle, and a project missed on one night
  is caught up on the next.
- **A delivery that arrives while the worker is stopping is no longer sent to the dead-letter
  topic.** It is delivered or redelivered as normal, instead of waiting an hour for the
  stranded-delivery sweep.
- **An alert notification is sent only once the alert is saved.** A failure while recording an
  alert no longer leaves a Slack message, email or webhook already sent, followed by a duplicate
  a minute later.
- **The outbox table no longer grows without limit on busy installations.** Hourly cleanup now
  deletes the whole backlog of published messages in batches, instead of at most 5,000 rows an
  hour.
- **The load-test receiver** caps its simulated delay at 60 seconds and no longer returns error
  details.

### Security

- **A "Continue with Google" sign-in only completes in the browser that went through Google.** The
  sign-in code is bound to that browser by a short-lived cookie, so a sign-in link someone sends
  you can no longer sign you into their account.
- **Sign-in cookies are Secure on any https deployment**, whenever `APP_BASE_URL` is https, not
  only when `APP_ENV=production`. Plain-http installs keep non-Secure cookies, which browsers
  would otherwise drop.

## [2.20.7] - 2026-09-14

### Upgrading

- **Email addresses become case-insensitive, and the upgrade refuses to guess.** Existing
  addresses are converted to lower case. If two accounts have addresses that differ only in case
  or surrounding spaces, the migration stops, naming their user ids, and the API does not start
  until all but one of them are changed or removed. Check first:
  `select lower(btrim(email)), count(*) from users group by 1 having count(*) > 1;`

### Changed

- **A resend keeps the delivery's history.** Resending a delivery, alone or in bulk, no longer
  resets its attempt count: it grants 3 more attempts, the first sent immediately, and later ones
  wait at the step of the retry ladder the delivery had already reached. `fromAttempt=N` grants
  the attempts that remained from attempt N. `maxAttempts` is capped at 100.
- **Alert rule emails go only to verified members of the organization**, at most 10 per rule.
  Other addresses are refused when the rule is saved.

### Fixed

- **A slow retry is no longer sent twice.** A retry that started more than five minutes after it
  was scheduled, for example under consumer lag, could be reset and sent to your endpoint again
  while the first attempt was still in flight.
- **Resending a delivery that is still in progress answers 409** instead of sending it a second
  time. Bulk resend without a status filter now picks only failed and abandoned deliveries.
- **Retried deliveries older than four days stay retried.** They went straight back to Failed
  Messages after a retry or resend; the age limit now counts from when you last retried the
  delivery.
- **Retrying or replaying an incoming webhook works after 24 hours.** The new forward gets its own
  24-hour window instead of inheriting the age of the original webhook, and
  `forward_oldest_pending_age_seconds` measures the same way.
- **Starting a Time Machine replay returns immediately**, and the replay runs in the background.
  Cancelling and the two-replays-per-project limit work while it runs, and one failing batch no
  longer discards the whole session.
- **A host that temporarily fails to resolve is retried.** A delivery or forward whose DNS lookup
  fails goes onto the retry ladder, and lands in Failed Messages if it never resolves, instead of
  failing permanently on the first error.
- **A provider resend is answered 202.** A provider resending a webhook Railhook already accepted,
  with the same provider event id, gets the stored event back, not 401 "replay attack", nor 429
  when the organization is over quota. The signature is still verified.
- **Form-encoded incoming webhooks verify.** Slack slash commands and interactivity, GitHub's form
  content type and generic HMAC senders posting forms were refused with 401; they are now
  verified, stored and forwarded as the exact bytes the provider sent.
- **Stripe webhooks verify while a signing secret is being rolled.** Any of the `v1` signatures
  Stripe sends may match.
- **Nightly data retention completes.** An event with a failed workflow trigger made the whole
  night's retention fail and roll back, every night. Batches now commit independently, so one
  failure no longer undoes the rest.
- **Plan retention keeps deliveries in flight.** Deliveries still pending or being retried, such
  as those created by a replay of an older event, are no longer deleted.
- **Usage and quota count incoming webhooks.** Monthly usage and quota checks now count incoming
  webhooks as well as events, and stay accurate after a Redis error or eviction, for every
  organization.
- **Alerts notify again after they recover.** An alert now resolves itself when its condition
  recovers, so the next outage notifies you instead of staying silent until someone resolves the
  old alert by hand. Resolved alert events older than 90 days are removed daily.
- **Email addresses are not case-sensitive.** A different-case copy of an existing address can no
  longer be registered, and Google sign-in and email change no longer break when one exists.

### Security

- **More internal addresses are refused as destinations.** Destination and endpoint URLs pointing
  at `[::]`, IPv6 multicast, or IPv6 forms of private IPv4 addresses (IPv4-mapped, NAT64, 6to4)
  are refused, both when saved and at connect time.

## [2.20.6] - 2026-09-14

### Fixed

- **Overview stays on the project you were in.** Overview, the projects list and the
  organization pages carry no project in their URL, and they fell back to the account's first
  project: working in one project and clicking Overview switched the dashboard, and the sidebar's
  project with it, to another. The dashboard now remembers the project you last opened, in this
  browser, and falls back to the first one only when that project no longer exists.

## [2.20.5] - 2026-09-14

### Fixed

- **A workflow can no longer write into another organization's project.** A `createEvent` node
  named its target project by id and nothing checked the id. Saved with another organization's
  project, the workflow stored Events there, and they were delivered to that organization's
  endpoints. Saving a workflow now refuses a project the caller cannot see, and an ingest into a
  project outside the caller's organization is refused wherever it comes from.
- **The workflow `delivery` node works.** It took the Event to send from `_eventId` in its input,
  which nothing set, so every delivery node failed with a database error — and a workflow that set
  `_eventId` itself could have sent another organization's Event to its own endpoint. The node now
  records its input as an Event in the endpoint's project and delivers that; `eventType` in the
  node's data names it (default `workflow.delivery`).
- **Events a workflow creates count against the month's quota before they are created**, not only
  after. Both the `createEvent` and `delivery` nodes check it, so a workflow cannot run past a limit
  the API would have refused.

## [2.20.4] - 2026-09-14

### Fixed

- **A CLI tunnel survives an API restart.** When the socket dropped — every rolling deploy drops
  it — the server closed the tunnel session, and the CLI's reconnect was refused as "Tunnel
  session not active". The tunnel stayed dead, and `railhook listen` had to be started again for a
  new URL that every provider then had to be given. A dropped socket now leaves the session open:
  the CLI reconnects to the same URL, a tunnel closed with Ctrl+C is still closed at once, and one
  nobody comes back to expires after the heartbeat timeout.
- **A tunnel that is not connected answers 503, not 502.** Behind a CDN a 502 is replaced by the
  CDN's own "Bad gateway" page, so a stopped tunnel looked like the whole site was down; a 503 also
  tells a provider to retry.

## [2.20.3] - 2026-09-14

### Fixed

- **Rate limits count each visitor again, not each CDN edge.** Caddy, left at its default,
  replaced `X-Forwarded-For` with the address that connected to it. Behind Cloudflare that address
  is the edge, so everyone reaching the site through one edge shared one sign-in limit of ten a
  minute, one registration limit, and one address in the audit log. The Caddyfile now passes the
  header on intact, and the API alone decides which hop is the client, against
  `WEBHOOK_TRUSTED_PROXIES` — walking from the right, so a forged entry on the left is ignored. An
  upgrade rewrites the Caddyfile, so existing installations get this without editing anything.
- **The CLI can be logged in again.** `railhook login` polls every five seconds, and each poll spent
  the sign-in rate limit of the address it came from. The browser approving the code is on that same
  address, so the approval was refused with "Too many requests" and the login never completed.
  Polling now has a budget of its own, per device code; approve and deny keep the sign-in limit, and
  the CLI backs off on a 429 instead of printing `?`.
- **A tunnel URL takes a path.** `https://<host>/tunnel/<slug>/webhooks/stripe` answered the API's
  own 404 and never reached the CLI: only the bare slug was routed. Every path below the slug is now
  forwarded, and reaches the local application as `/webhooks/stripe`.
- **A test endpoint keeps the body of what it captured.** The body was read twice — once to answer
  verification challenges, then again to store it, from a request whose stream was already spent —
  so every captured request was saved with an empty body.
- **Sending events no longer counts against itself.** The worker's per-project delivery cap and
  the API's per-project ingest limit used the same Redis key, so every delivery attempt spent a
  permit from the ingest limit. A Free project sending 8 events a second against its 10-a-second
  limit had over a third of them refused with 429, and the delivery cap meant to be 50 a second
  was quietly held to the plan's 10. The two budgets now have keys of their own.
- **The CLI no longer signs you out everywhere half an hour after logging in.** A refresh returned
  the rotated refresh token only as a cookie. The CLI, which sends and reads the token in the body,
  kept the one that refresh had just rotated away; its next refresh replayed it, and reuse
  detection — correctly, for what it could see — revoked every session the user had, the browser's
  included. A client that sends the token in the body now gets the rotated one back in the body.
- **An incoming source created with a signing secret verifies with it.** Over the API, a source
  given a secret but no `verificationMode` was saved with verification off — a Stripe source with
  its signing secret accepted forged and unsigned requests alike. A secret without a mode now means
  the provider's own verification (`PROVIDER`), or `HMAC_GENERIC` for a generic source. A source
  created without a secret, and any mode set explicitly, are unchanged. The dashboard always sent a
  mode and was not affected.
- **Every new project masks email, phone and card numbers from the start.** The docs said so; the
  code created projects with no masking rules at all, so customer email addresses showed in full on
  every event and delivery in the dashboard until someone found the seed button. New projects now
  get the three built-in rules. Existing projects keep what they have — add the defaults from the
  project's PII rules page.
- **A workflow created over the API opens in the builder.** The API describes a node as `id`,
  `type` and `data`, and an edge as `source` and `target`; the canvas also needs a position and an
  edge id, and threw "Cannot read properties of undefined (reading 'x')" on a workflow that had
  neither. Nodes without a position are laid out left to right, and edges without an id get one.
- **Kafka keeps its topics when its container is recreated.** The Compose service mounted a volume
  at `/var/lib/kafka/data` but never told the broker to write there, so the log lived inside the
  container: any change to Kafka's settings — the heap limit in 2.20.2, for one — recreated it and
  took every topic, consumer offset and unread message with it. On production the two DLQ topics
  did not come back, and the worker failed every minute to read them. The broker now writes to the
  volume, and the worker creates any missing topic when it starts. Deliveries themselves are kept
  in Postgres and were retried; what a recreate lost was in flight. The upgrade to this release
  starts Kafka on the empty volume once.
## [2.20.2] - 2026-09-14

### Fixed

- **Kafka no longer fills its memory limit the moment it starts.** Without a heap setting, the
  Kafka image reserves a 1G heap up front — the whole default 1G container limit — leaving nothing
  for the rest of the JVM. A near-idle broker sat at 91% of its limit, one spike away from being
  killed and taking deliveries down while it restarted. The broker now runs with a 512m heap
  (Kafka keeps its data in the page cache, not the heap), set through `KAFKA_HEAP_OPTS` and
  documented next to `KAFKA_MEMORY_LIMIT`.

## [2.20.1] - 2026-09-14

### Fixed

- **Every Grafana dashboard shows real data.** Before this release a third of the panels read
  "No data" on a production host:
  - **Containers:** cAdvisor 0.60 reads containers on Docker 29, which stores images through
    containerd and left the old cAdvisor exporting nothing. OOM kills are counted from the
    kernel, so "OOM kills (24h)" is real rather than a 0 with no source. CPU reads as a share of
    the host's cores, and a recreated container is one line, not one per container id — so one
    restart or OOM is one alert.
  - **Kafka:** the api and worker publish their Kafka client metrics — consumer lag, records and
    bytes consumed, fetch latency, send rate, queue time.
  - **Latency:** HTTP requests carry latency buckets, so the p50–p99 panels have something to read.
  - **Product counters** — events ingested (outgoing and incoming), duplicates, fan-out limits,
    deliveries created, rules matched and dropped, retention cleanup — exist from start-up, so a
    quiet deployment shows 0.
  - **Errors, 5xx and alerts** panels show 0 or "No alerts firing" when nothing is wrong, instead
    of "No data".
  - "Total Delivery Attempts" reads the gauge under the name Prometheus exports.
- **The delivery and incoming forward failure-rate alerts can fire.** Their expressions added two
  series with different labels, which never matched, so they could not fire at all.
- **The outbox depth gauge works.** Prometheus scrapes read it without a tenant scope, the count was
  refused, and every scrape logged a warning and exported NaN — which also silenced the alert on
  messages stuck sending.
- **Logs are readable in Grafana.** One level vocabulary for every service (error, warn, info,
  debug), no Spring Boot banner, one line per entry as level, logger and message with the stack
  trace in the expanded view, and error counts named by service instead of "Value #A".
- **The Host dashboard lists the server by name**, not by the node-exporter container's id
  (`MONITORING_NODENAME`, defaulting to the machine's hostname).
- **Alert mail you can read and act on.** The subject says what happened and where —
  `[WARNING] The kernel killed a process for memory — railhook.io` — and a resolved alert says
  RESOLVED. The body leads with the summary and description, then the labels. Its links go to
  Grafana on `MONITORING_DOMAIN`; before, they pointed at Alertmanager and Prometheus, which are
  not published, so they led nowhere. Without a domain the mail says how to open Grafana through
  an SSH tunnel.
- **The platform admin panel lays out properly.** Empty states sit centred inside their cards,
  every table fits its card at 1440px, long email addresses and organization names end in "…"
  with the full value on hover instead of breaking mid-word, and column titles stay on one line.

### Changed

- Two metrics carry the names Prometheus actually exports: `delivery_attempts_total` is now
  `delivery_attempts_stored` (a gauge cannot end in `_total`) and `deliveries_created_total` is
  now `deliveries_total` (Prometheus drops a trailing `_created`). A Prometheus of your own that
  queried the old names found nothing under them before either.

### Added

- **Naming the platform admins on a self-hosted install:** `install.sh --admin-email you@company.com`
  (several addresses separated by commas) writes `PLATFORM_ADMIN_EMAILS`, on a new install or with
  `--refresh`. Without it nobody is a platform admin; no account becomes one by registering first.
- **The platform admin panel says what it is.** Every view opens with who can see it and that the
  only change it makes is suspending or reinstating an organization, linked to the docs. Platform
  admins carry a "Platform admin" badge in the users and members lists, and a plan without limits
  reads "Unlimited".
- `make monitoring-check-queries` runs every dashboard and alert query against the running
  monitoring stack and fails on any that return no data, apart from a short allow-list of panels
  that stay empty until something happens.

## [2.20.0] - 2026-09-13

### Added

- **A first project for Google sign-ups, a way in for everyone else.** An account created with
  "Continue with Google" starts with "My first project", so the dashboard opens ready to use; one
  registered with a password names its own first project. Without a project, every sidebar section still
  opens: it says what it is for and offers "Create project", then continues to that section. The
  overview shows a getting-started checklist.
- **Platform admin panel** (`/admin/platform`) for operators whose verified email is in
  `PLATFORM_ADMIN_EMAILS`: platform overview, every organization and user, usage against quotas,
  recent sign-ups, and suspension with a typed confirmation. Every admin request is audited, rate
  limited, and needs a sign-in from the last 12 hours; no secrets, keys or payloads are returned.
- **Monitoring you switch on with one command.** `./railhook monitoring up` starts Grafana,
  Prometheus, Loki, Alertmanager, node-exporter and cAdvisor for the installed release, with
  dashboards for alerts, errors, logs, uptime, containers and the host, and email alerts for a
  full disk, memory, 5xx and error spikes, failing deliveries, a stale backup, an expiring
  certificate and a site that stops answering. Grafana has no default password and is the only
  published port, on `127.0.0.1`; `MONITORING_DOMAIN` puts it behind Caddy, and
  `MONITORING_TLS_CERT`/`MONITORING_TLS_KEY` give Caddy a certificate for it when Let's Encrypt
  cannot reach the name (Cloudflare with Always Use HTTPS).
- **The production deploy proves the site works page by page** — landing, docs, auth pages,
  legal pages, installers, runtime config, API and health — not only that `/` answers 200.
- **Change the address you sign in with.** In Settings, or from the verification banner. An
  unverified account changes it at once and gets a fresh link at the new address. A verified one
  confirms with its password (or a recent Google sign-in), and nothing changes until the new
  address confirms; the old address is told, with a "this wasn't me" link that cancels the change.
  Confirming or cancelling signs every session out. Changes and confirmation emails are capped per
  day, and every request, confirmation and cancellation is in the audit log.
- **"Did you mean gmail.com?"** Registration, member invites and the billing email suggest the
  likely address for a mistyped domain, and endings that do not exist (`.con`, `.cmo`, …) are
  refused before any mail is sent. Signing in is not checked, so an account with a typo can still
  get in and fix it.
- **Every email Railhook sends is logged**, with the template, a masked recipient and whether the
  provider accepted it — so "the email never came" has an answer.
- The Railhook Cloud limits are documented next to the self-hosted ones.

### Security

- PII masking reads each key and value once and decides in code whether it is an email, phone or
  card number, and finds a card object by reading back from its member; a crafted payload of a few
  hundred kilobytes could otherwise pin a thread. Transformation template validation is
  linear-time too, and the PII preview is always `text/plain`.
- Database dumps are written readable by their owner only.
- The deploy step receives only the secrets it sends; third-party GitHub Actions are pinned to
  commits.

### Fixed

- The Trivy scan installs a pinned version and retries, instead of failing a required check when
  one download hiccups.
- **A busy dashboard no longer signs you out.** Refreshing a session has its own rate limit,
  per session rather than shared with sign-in attempts from the same network, and the dashboard
  retries a refresh that answers 429 or 5xx instead of ending the session.
- Copy-as-curl and API key snippets send to the address the dashboard actually talks to; the tunnel
  empty state shows the real `railhook listen` command; source pages show the provider's own
  signature header, and a provider-signed source says to send a test from the provider instead of
  offering an unsigned curl.
- Signing in from an origin the server does not allow explains that, and names
  `CORS_ALLOWED_ORIGINS`, instead of an empty 403.
- `APP_BASE_URL` defaults to `http://localhost`, matching the UI's default port.
- Retry schedules say that each wait varies by design; smaller wording and formatting fixes across
  the dashboard.

## [2.19.2] - 2026-09-13

### Fixed

- **Test endpoint URLs are public.** They were built from `TEST_ENDPOINT_BASE_URL`, which Compose
  defaulted to `http://api:8080` — the API's name inside the Docker network — so production showed
  `http://api:8080/hook/…`, a URL nothing outside could reach. It now defaults to `APP_BASE_URL`,
  and the Helm chart passes the public origin too. Existing test endpoints show the right URL at
  once: it is built per request, not stored.
- **The free plan has every feature.** Workflows, rules, replay and mTLS were off on free with
  "Please upgrade" as the only way on, while no paid plan can be bought. Free is now bounded by its
  quotas alone (migration V072). The billing page ticks the features from the plan the API returns,
  and shows the free plan's price as `$0/mo` instead of a second "Free" next to its name.

## [2.19.1] - 2026-09-13

### Fixed

- **Railhook on a phone.** Below the `sm` breakpoint the product has a layout of its own instead of
  a shrunken desktop; desktop is unchanged.
  - Landing: a one-line install command with an icon copy button, mobile versions of the send and
    receive scenes with legible labels, a readable crop of the product screenshot that opens full
    size, a two-column footer, and 40px tap targets throughout.
  - Dashboard: record lists (deliveries, events, endpoints, connections, dead letters, audit log
    and the rest) render as cards with labelled values, filters fold behind a Filters button, and
    buttons, tabs, pagination and selection are 40px targets.
  - Docs: the language tabs over code samples scroll instead of running off the page.
- **Link previews show the current design.** The social card is the new hero, published under a new
  file name so services that cache images by URL fetch it.

### Added

- **Browser layout tests.** A Playwright job checks public, auth and dashboard pages at phone and
  desktop sizes: nothing wider than the screen, headings in view, 16px form fields and 40px targets
  on phones.

## [2.19.0] - 2026-09-13

### Added

- **Sign in and sign up with Google.** A "Continue with Google" button on the login and registration
  pages creates a verified account and its organization in one step, or signs in to — and links —
  an existing account with the same address. Authorization code with PKCE and a nonce; the
  id_token is verified against Google's keys; the browser gets a single-use sign-in code, never a
  token in a URL. It is on only when `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` are
  set: a self-hosted install without them shows no button and the endpoints answer 404.
- **Privacy Policy and Terms of Service** pages (`/privacy`, `/terms`), linked from the footer and
  from the consent line on the registration page.

### Fixed

- **A flaky integration test.** The workflow outbox reclaim test counted rows other tests had left
  in the table; each test now starts from an empty one.

## [2.18.1] - 2026-09-13

### Fixed

- **The favicon renders.** `/favicon.svg` had `--` inside an XML comment, which makes the file
  malformed; browsers refused it and kept showing whatever icon they had cached. A test now parses
  every SVG the app and the docs ship.
- **Registration says what the password is missing.** An unmet rule was a faint grey cross next to a
  disabled button; unmet rules are red now and a line under the field names what is still needed.
- **Forms no longer zoom on iPhone.** Fields were 14px, and iOS Safari zooms into any focused field
  under 16px and stays zoomed, which left the registration form cut off at the right edge. Fields
  are 16px on phones and 14px from the `sm` breakpoint up.

## [2.18.0] - 2026-09-13

### Added

- **The landing page shows delivery happening.** The send and receive cards carry live scenes —
  an event fanning out to three endpoints, one answering 503 and succeeding on retry; Stripe,
  GitHub and Shopify requests verified and a forged one rejected — each with a running delivery
  log. A quiet delivery backdrop sits behind the hero. Motion pauses offscreen and in a hidden tab,
  and `prefers-reduced-motion` gets a static final frame.
- **Vendor logos on the maps.** The hero map and the architecture diagram use each vendor's own
  mark, Slack included.

### Fixed

- **Unknown URLs answer 404.** They used to return 200 with the landing page and a canonical to
  `/`. The app's own routes still get the app shell, now with `X-Robots-Tag: noindex`, and a test
  fails when the router gains a top-level route nginx does not know.
- **`/pricing` redirects** (301) instead of serving a duplicate of the landing page, and is gone from
  the sitemap.
- **The docs carry a social image and a `lastmod` per page** in their sitemap.

## [2.17.2] - 2026-09-13

### Added

- **Production settings are sent with the deploy.** `deploy-prod.yml` turns every `DOTENV_<NAME>`
  variable and secret of the GitHub `production` environment into `NAME=value` and pipes them into
  the deploy; `./railhook upgrade` applies them to `.env` before it changes anything. Changing a
  setting is an edit in GitHub and a redeploy, not a root shell on the host. The helper's new
  `./railhook settings < file` does the same by hand. Neither prints a value, and both refuse the
  encryption key and salt, `JWT_SECRET`, the database and Redis passwords and the image tags.

## [2.17.1] - 2026-09-13

### Fixed

- **The published UI image serves any domain.** The site URL (`APP_BASE_URL`) and the CAPTCHA
  site key (`CAPTCHA_SITE_KEY`, `CAPTCHA_SCRIPT_URL`) are read by the UI container at startup
  instead of being baked in at build time, so no deployment needs a UI image of its own — and
  `railhook upgrade` can no longer leave the site on the previous release behind a pinned one.
  `VITE_SITE_URL`, `VITE_CAPTCHA_SITE_KEY` and `VITE_CAPTCHA_SCRIPT_URL` are gone; see
  UPGRADING.md.
- **`railhook upgrade` replaces the UI in a step of its own.** Recreated in one call with the
  data services, Caddy and the worker, the UI was down for about 45 seconds and the site answered
  502. Caddy's retry window is also 30 seconds now.
- **The production deploy checks the version the site serves**, not only that it answers 200.
- **A hosted deployment can enforce the free plan without a payment provider.** `BILLING_ENABLED=true`
  with `BILLING_DEFAULT_PROVIDER=noop` used to be refused at startup, so a cloud with no paid plans
  had to run with billing off — and billing off means no quotas at all. It is now the free-plan-only
  mode: quotas are enforced, the plan catalog lists nothing priced, checkout is refused, and the
  billing page shows no plan picker with nothing in it.

## [2.17.0] - 2026-09-13

A new site, docs you can navigate, and one install command on the project's own domain.

### Added

- **Docs site at `/docs/`**, in English and Ukrainian. Guides grouped by what you are doing
  (get started, self-hosting, sending, receiving, platform), full-text search that works
  offline, and an API reference with "Try it" that calls the instance serving the page. The
  configuration reference is generated from `.env.dist`, so it cannot drift from it.
- **`curl -fsSL https://railhook.io/install.sh | bash`.** Every UI image serves the installer
  at `/install.sh`.
- **`install.sh --domain <host> --behind-proxy`** for a reverse proxy you already run: production
  settings on your domain, the dashboard on loopback, no TLS terminator — nothing left to edit
  in `.env` by hand.
- **New look across the product**: white ground, cobalt accent, Manrope and Onest. The landing
  page puts Railhook Cloud (free right now) and the self-hosted install side by side, shows what
  Railhook is built on (sources → API → Kafka → worker → endpoints, over PostgreSQL and Redis),
  and ends with a developer section: sending an event in Node.js, Python, PHP and cURL, and
  links to the docs, the API reference and Standard Webhooks.
- **`curl -fsSL https://railhook.io/install-cli.sh | bash`** installs the CLI; every UI image
  serves it next to `/install.sh`.
- **`scripts/seed-demo.sh`** seeds a believable demo project — endpoints, subscriptions, sources,
  delivered, retrying and failed traffic — through the public API only.
- **"Connect with us" in the public footer**: the GitHub repository, and support mail when the
  deployment has a contact domain.

### Changed

- **The contact page's mail domain is set at runtime.** `RAILHOOK_CONTACT_DOMAIN` on the UI
  container (`ui.contactDomain` on Helm) decides the sales@ / support@ addresses, so the
  published image can offer them; empty, as on a self-hosted install, offers none.
  `VITE_CONTACT_DOMAIN` is gone — see UPGRADING.md.

### Fixed

- **Incoming-source and tunnel URLs pointed at `http://localhost:8080`** on every `--domain`
  install and every Helm release, so providers and the CLI were handed an address nothing
  published. They now follow `APP_BASE_URL` (Compose) or `app.baseUrl` (chart);
  `TUNNEL_INGRESS_BASE_URL` / `app.ingressBaseUrl` still override.
- **`ENTITLEMENT_DEFAULT_RATE_LIMIT` and `ENTITLEMENT_DEFAULT_MAX_FANOUT` never reached the API**
  under Compose; `.env.dist` documented them and nothing passed them through.
- **The dashboard's HTML was served without `X-Frame-Options`** and the other security headers:
  nginx drops server-level `add_header`s in any location that sets one of its own, and the
  cache headers did exactly that.
- **Toasts stayed light on the dark theme.**
- **SDKs**: the Node SDK accepted a signature whose timestamp was not a number (the tolerance
  check was skipped); the Python SDK answered malformed signature headers with an unhandled
  exception instead of `RailhookError`; the PHP SDK rejected the array-valued headers Laravel and
  Symfony pass. The READMEs' Stripe example could never verify and said deliveries are `PUT`
  (they are `POST`).
- **The API reference's "Try it" client was painted over** by the docs sidebar and header.
- **Every UI image build reinstalled Chromium** for the prerender step whenever any source file
  changed; it is now installed before the sources are copied, so that layer is cached.
- **Browsers kept showing old images after an upgrade.** nginx cached every `.png`, `.svg` and
  `.ico` as immutable for a year by extension, including files whose names never change (the
  favicon, logos, landing screenshots). Only content-hashed paths (`/assets/`, `/docs/_astro/`)
  are immutable now; everything else revalidates. The landing screenshots are hashed as well.
- **The docs header drew a second line** under the search box.

### Removed

- The in-app guides and the Redoc page. Old `/docs/<section>` addresses no longer resolve; the
  pages live under `/docs/<group>/<page>/`.
- The pricing page and the cloud plan grid.
- Installer support for releases older than 2.12.0, the `HOOKFLOW_*` variable names, and the
  `--write-helper` alias for `--refresh`.

## [2.16.0] - 2026-09-12

An upgrade no longer stops the API to replace it. Measured on a production host, same probe
before and after: **32 seconds of 502, then none**.

### Fixed

- **`railhook upgrade` rolls the API instead of restarting it.** Three things were wrong and
  only the third was expensive.

  nginx resolves a name in a literal `proxy_pass` once, at startup, and keeps that address for
  the life of the process — so a recreated container came back on a new one and nginx went on
  posting to an address that no longer answered. Resolving through a variable makes it look the
  name up per request. Worth about seven seconds of the thirty-two; the rest was the JVM
  starting, which no proxy setting shortens.

  So the API has to be *replaced* rather than restarted, and it could not be: `container_name`
  pins a service to one container and Compose refuses to scale it at all. Removed, with
  `API_REPLICAS` defaulting to 1 — the smallest supported host has room for one JVM here — and
  2 for anyone who would rather not have the gap.

  And the part that makes scaling alone insufficient: **Docker's embedded DNS publishes a
  container's address the moment it exists and does not withhold it while the healthcheck is
  still failing.** Simply scaling up therefore hands nginx a share of live traffic for a cold
  JVM — turning a total outage into a half one. The replacement is now created stopped, attached
  under a throwaway alias, started, waited for, and only then given the name nginx resolves. The
  container it replaces is drained a resolver TTL later with `SIGTERM`, so Spring's graceful
  shutdown finishes what is in flight after nginx has stopped sending it work.

- **`railhook upgrade` refreshes `docker-compose.yml`.** It moved the image tags and nothing
  else, so anything a release changed about the topology reached new installations only —
  including the replica count the rolling path above depends on. The previous file is kept
  beside it rather than merged, because a merge that got it wrong would surface at the worst
  moment.

### Changed

- `docker logs webhook-api` no longer resolves — the API has no fixed container name so that it
  can be rolled. Use `docker compose logs api`, which works for one replica or several. Every
  other service is unchanged.

## [2.15.0] - 2026-09-12

Four faults that each made the system quietly do the wrong thing rather than fail visibly, plus
the CAPTCHA wiring that made a security setting impossible to turn on.

### Fixed

- **A webhook is verified against the bytes that arrived.** The controller took the body as a
  `String`, which Spring builds by decoding with whatever charset the `Content-Type` declared,
  and every verifier then encoded it back as UTF-8 before computing the HMAC. For a sender that
  used anything else those are different bytes, so a **genuine webhook failed verification** —
  and nothing in the request explained it, because the signature really did not match the thing
  being hashed. Stripe's and Slack's timestamp prefixes are now joined to the body at the byte
  level for the same reason. `bodySha256` is over the arrived bytes too, so the stored digest is
  of the request rather than of our copy of it.

- **An ordered Delivery no longer loses its ordering permanently.** The sequence number is
  assigned just after the ingest transaction commits, deliberately — it comes from Redis, and a
  Delivery the customer was told about must not be undone because a counter was unreachable.
  What no catch block covered was the process ending: a pod dying in that window left the row
  with a null sequence for ever, and the worker then delivered it unordered without telling
  anyone. `SequenceReconciliationService` now sweeps and backfills those, and
  `webhook_sequence_stranded_total` counts them — anything but zero means ingest processes are
  dying mid-request.

- **A workflow's delivery node writes the Delivery and its announcement in one transaction.**
  It had neither an annotation nor a template, and the workflow engine runs nodes on their own
  pool, so there was no ambient transaction to inherit: two auto-commits with a window between
  them that left a `PENDING` Delivery with no Outbox row and `next_retry_at` NULL. Nothing
  dispatches such a row; it waited an hour for the stranded-PENDING sweep.

- **The dashboard rollups are confined by the query rather than by convention.**
  `MaterializedViewRepository` is raw JdbcTemplate, so `@TenantId` never reached it, and the
  views carried only `project_id` — there was not even a column to filter on. It was safe only
  because both callers load the `Project` under tenant scope first. `V070` rebuilds both views
  with `organization_id` and the predicate comes from `TenantContext.require()`. An unreferenced
  method that took a list of ids with no ownership check at all is deleted.

- **The CAPTCHA can be turned on.** `VITE_CAPTCHA_SITE_KEY` was documented, read by the CSP
  builder, and passed by nobody — no `ARG`, no build arg. Setting it did nothing, and the
  failure was worse than inert: with the API's secret set and no site key in the bundle, the
  page sends no token and **every registration is refused**. Underneath it, nginx sent a second,
  static CSP whose `script-src 'self'` would have blocked the widget anyway, since a browser
  enforces the intersection of header and meta policies.

### Changed

- `NativeQueryTenantPredicateTest` also fails a repository that holds a `JdbcTemplate` without
  mentioning `TenantContext`. It scanned `@Query(nativeQuery = true)`, which is every way into
  Hibernate but not every way into the database.
- A test asserts that every `VITE_` variable in `.env.dist` reaches the build as an `ARG`, an
  `ENV` and a Compose build arg — the class of fault, rather than the one instance of it.

## [2.14.0] - 2026-09-12

Reliability work ahead of the first production deployment. Sixteen faults, each reproduced with
a failing test before it was touched. No new features, no API changes.

### Fixed

#### A webhook arriving twice at a receiver that already had it

- **A 2xx whose body arrives after the timeout is a success.** The timeout sat on the outer
  reactive chain, so firing during the body read *cancelled* the inner chain rather than failing
  it — and the `onErrorResume` written for `AttemptRunner`'s invariant 6 never saw it. The
  `TimeoutException` surfaced as "the request failed" and the whole ladder ran against an
  endpoint that had taken the event. The status is now stashed as the response head lands, so
  the outcome is decided by the status however the body ends.

- **A database that blinks while writing down a delivered webhook no longer re-sends it.**
  `recordAttempt` and `finalise` sat inside the same `try` that catches request failures. Worse
  on the failure paths, where `fail()` recorded a second time, threw a second time and escaped
  the Runner entirely — the consumer then acked the record and left the row `PROCESSING` with
  nothing saying why. Recording is observability; the finalisation is the ownership transfer.

- **A transformation that cannot run now costs a rung.** The rung was spent in `attemptStarting`,
  which ran *after* `buildBody`, so nothing that threw on the way to the wire advanced the
  ladder. `isExhausted` never became true and the delivery retried at the same rung every minute
  until the 96h cap — roughly 5,700 attempt rows for one delivery nothing was going to send.

- **An outbox callback that outlives its batch can no longer settle a reclaimed row.**
  `batchMarkPublished` and `batchMarkFailed` matched on id alone, so a straggler stamped a stale
  outcome over whatever the next cycle was doing. Guarded on `status = 'SENDING'`, and the
  shortfall is reported — it is the only visible sign that the send timeout is tuned below what
  the broker takes.

#### Work that stopped getting done

- **One `SQLException` no longer holds up a Kafka partition until restart.** `processForward` had
  no catch, and `BoundedAsyncExecutor` reads a throw as "do not ack" on purpose; with async acks
  an unacked offset blocks every commit for its partition. The outgoing direction always caught
  and acked — the asymmetry was an omission.

- **Stuck-claim recovery no longer depends on the Redis that is down.** `ExclusiveSweep` caught
  only `InterruptedException`, so a Redisson failure escaped the `@Scheduled` method and neither
  stuck-delivery nor stuck-forward recovery ran during a Redis outage — which is exactly when
  workers restart and Claims are lost. It now sweeps without the lock and counts it, the way the
  circuit breaker already fails open.

- **An outbox row that never gets a callback can now reach `DEAD`.**
  `recoverStuckSendingMessages` did not increment `retry_count`, and `promoteExhaustedToDead`
  only looks at `FAILED`, so such a row cycled `PENDING → SENDING → PENDING` for ever. The
  default send timeout is 30s against Kafka's own 120s, so this was the common path.

- **A null error message no longer kills the publish cycle.** `ConcurrentHashMap` refuses a null
  value and `Throwable.getMessage()` is null often enough — an NPE inside a serializer is the
  ordinary case. In the send callback it left the row `SENDING`; in the preparation catch it
  propagated out and abandoned every row the cycle had claimed.

- **`publishedIds` is snapshotted before the repository iterates it.** A `synchronizedList`
  handed straight to Spring Data is iterated without its monitor while late callbacks may still
  be adding.

#### Secrets that were not meant to leave

- **The delivery dry-run no longer mints a signature for another project's endpoint.**
  `@TenantId` confines the lookup to the organization and the interceptor confines the
  `{projectId}` in the URI, but the endpoint id arrives in the request *body*, outside both — so
  an API key issued against one project could obtain a valid `X-Signature` over a body of its
  choosing for a sibling project's endpoint, plus the URL to aim it at.

- **Password reset links are no longer logged in production.** The dev affordance stays — with
  no SMTP it is the only way to complete a reset on a workstation — and stops at the environment
  boundary. The `Fallback` logs go entirely: that branch only runs with email *enabled*, and one
  refused relay is not a reason to put a reset link in a log file.

- **Test-endpoint captures and tunnel request logs mask credentials.** Both reimplemented the
  header loop without the masking the other three capture paths apply, so `Authorization` and
  `Cookie` landed in the database and were rendered in the dashboard. Masked at the write, not
  at the display.

- **The encryption key is derived once, not on every encrypt and decrypt.** PBKDF2 at 65,536
  iterations was being paid per call against a process-wide salt, so the same bytes were
  recomputed thousands of times a second. `/ingress/{token}` decrypts the source's HMAC secret
  *before* the signature is checked, making that cost bookable by anyone who knows the token —
  measured at ~15ms per call.

#### One tenant taking what belonged to the others

- **Endpoint verification no longer waits on a customer's server inside a transaction.** Up to
  ten seconds holding a Hikari connection and a row lock, reachable from a user-facing endpoint;
  a handful of concurrent verifications against slow targets drained the pool for the whole
  instance. It also built a fresh connection pool per call.

- **The per-project workflow ceiling now holds.** Admission and increment were two statements and
  the map entry was evicted at zero, so a thread could increment an `AtomicInteger` no longer
  reachable from the map, after which the count and reality diverged permanently.

- **The client-error throttle's bookkeeping no longer outlives the throttle** — one map entry per
  user for the life of the process, for a window that lasts a minute.

#### Measured rather than read

- **A workflow no longer runs concurrently with itself.** `reclaimStalledRows` compared
  `created_at`, the moment the event was ingested, and there was no column recording when the row
  was claimed. A row is deliberately held in PENDING while its project is at its ceiling, so on a
  busy project rows were `created_at`-stale before they were ever claimed — the sweep returned
  them to PENDING while a live executor was running them. `V069` adds `claimed_at`.

- **The per-endpoint announcement ceiling is visible and movable.** `load/ingest.js` at 50 rps
  against one endpoint drained the outbox at exactly ten rows a second: `maxPerKey` was a literal
  `10` where the bounds either side of it were both configurable. The default does not move — the
  fairness it buys is real — but an operator with one busy endpoint can now see it and raise it.
  `OUTBOX_MAX_PER_PROJECT` also turned out to be documented and never plumbed through Compose.

### Changed

- **Kafka producer retries are bounded by time, not by count.** `retries=3` on an idempotent
  producer is roughly 300ms of patience, so an ordinary leader election failed sends the default
  would have ridden out. `delivery.timeout.ms` and `max.block.ms` are now declared and
  configurable; the inert `spring.kafka.producer.*` keys in `application.yml` are replaced with
  the two that are actually read.

### Added

- **A backup round-trip test.** `BackupFlagParityTest` compared `pg_dump` flags across the three
  places that run it and said nothing about whether the output restores or whether what comes
  back still works. This dumps, restores into a separate database, and proves the round-tripped
  ciphertext still decrypts, that an in-flight Delivery comes back `PROCESSING` holding its fence
  token, and that the stuck sweep is what moves it.

### Security

- Five of eight dependency advisories closed: `js-yaml`, `minimatch`,
  `postcss-selector-parser`, and `puppeteer-core` (which carried the two unfixable `extract-zip`
  advisories). All eight were devDependencies and none reaches the browser bundle;
  `npm audit --omit=dev` was clean before and after. The remaining three are `vitest` — see
  `UPGRADING.md`.

## [2.13.0] - 2026-09-07

### Added

- **`railhook upgrade [version]` takes a backup first**, pins the image tags, and refuses to
  continue if the backup fails. It used to print "edit the tags in .env first, then:" and run
  pull. Rolling back is not symmetric and now says so: the images go back, the schema does not —
  Flyway is forward-only here — so the backup is what makes the difference between a bad release
  and a bad migration recoverable. `railhook backup` was also quietly wrong: it hardcoded the
  database name and read the user from the invoking shell rather than from `.env`, which it never
  sourced.

- **A disaster-recovery procedure**, in `docs/OPERATIONS.md`. Most of it already existed in the
  code and had never been written down — the document said twice that there was no procedure for
  reconciling Postgres, Kafka and Redis after a restore, while `SequenceReconciliationService`,
  `QuotaCounterService` and `StuckDeliveryRecoveryService` between them handle all but one step
  of it. That step is flushing Redis. Also: what the shipped backup schedule buys as RPO and RTO,
  and that restoring onto a new host needs `.env` more than it needs the dump.

- **A per-organization API rate limit**, off by default. `GlobalRateLimitFilter` holds one bucket
  for the whole platform, so on a shared installation one tenant looping over their deliveries
  spends everyone's budget. Off by default because on a self-hosted installation every tenant is
  the operator's own and the check costs a Redis round trip per request.

- **A person can erase their own account** — `DELETE /api/v1/auth/me`, and a Danger zone in
  Settings to reach it from. The platform could erase a whole customer and could not erase one
  human being: an individual who is a member of somebody else's organization had no way to
  remove their own record, which is the half of Article 17 that individuals actually exercise.

  The identifying data goes and the account is made permanently unusable — an unroutable
  `.invalid` address, no name, a password hash nobody holds, every session closed and every
  membership removed. The row itself survives, anonymised, because the schema decides it:
  `shared_debug_links.created_by` references `users(id)` with no cascade, so deleting the row
  outright fails for anyone who ever shared a debug link, and `audit_log.user_id` has no foreign
  key at all, so what someone did outlives them — the point of an audit log, and a legitimate
  basis under 17(3)(b).

  An organization the person was alone in is deleted with them, because otherwise erasure leaves
  every event and delivery it owned in the database with nobody able to reach or erase it. The
  last owner of an organization that still has other members is refused with `409` and told to
  hand it over first: leaving it ownerless would strand everyone else.

- **Both erasures and the export are audited.** `deleteOrganization` carries a javadoc citing
  Article 17 and destroys every row a customer has; it left behind a log line, which is on a
  retention clock of its own. `exportOrganizationData` puts every member, project, endpoint and
  key into one file that somebody then carries around. Neither had an audit entry.
  `GdprOperationsAreAuditedTest` keeps it that way — an explicit list, because "this is a data
  subject's right" is a judgement about the law rather than something a signature carries.

- **The dashboard reports its own failures.** A render error reached `console.error` in one
  person's browser and stopped there, so from the server a screen that threw for every customer
  was indistinguishable from a screen nobody had opened.

  The reports go to a new endpoint in this installation and nowhere else — no third party, no
  DSN, no account to create. With the `production` profile now activating the JSON appender,
  a dashboard failure lands in Loki beside the correlation id of whatever request the page was
  making when it broke, and a self-hosted operator reads it in their own logs like everything
  else. `CLIENT_ERROR_REPORTING_ENABLED` turns it off.

  That moves the risk rather than removing it, because the string on the log line is now one a
  browser chose. `ClientErrorReportService` therefore strips every character that could end a
  line, bounds each field, drops the URL's query string — where a share token would be — and
  caps how often one user can write, so a component throwing on every render cannot produce a
  log line per frame. On the client, `reportClientError` never throws, never retries, reports
  each distinct failure once, and stays quiet with no session.

### Fixed

- **The nightly usage sweep loaded every project on the platform into memory at once.**
  `findAll()` inside a scheduled job holding a lock with a deadline; it walks pages of 500 now,
  and takes a projection rather than the entity. It also did not exclude soft-deleted projects,
  so every deleted project was counted and written a usage row every night, forever.

- **The dead-letter recoverer copied the source partition onto the DLQ topic**, which is correct
  only while the DLQ is at least as wide as the topic it shadows. Repartition a main topic upward
  to scale its consumers — the ordinary thing to do — and every dead letter from a partition the
  DLQ does not have fails to publish. The broker picks now; the record keeps its key, so ordering
  per delivery is unchanged.

- **The README badge and CLAUDE.md said Spring Boot 3.5**, months after the upgrade to 4.1. The
  badge is the first thing an evaluator reads. `DeclaredStackVersionTest` ties both to the pom.

- **The GDPR export was quietly short, and the guide described data it does not contain.** It
  caps audit entries at 10,000 and said nothing about it, so a subject-access response could be
  incomplete and look whole; it now carries `auditLogsTruncated` and `auditLogsTotal`. And
  the data-retention guide promised the export included Events, Deliveries and Attempts.
  It never has — those are the payload tables, and they are aged out by retention instead. The
  guide says so now, along with what each of the two erasures actually does.

- **A javadoc claimed the audit log was deleted along with its organization.** `audit_log`
  carries an `organization_id` and no foreign key, so its rows outlive the organization they
  describe — which is exactly what makes auditing an erasure meaningful.

- **A response body too large to read turned a delivered webhook into a retry.** `AttemptRunner`
  read the receiver's response with the WebClient codec default of 256 KiB. A receiver that
  accepted the webhook and answered `200 OK` with a larger body threw `DataBufferLimitException`,
  the throw was caught as "the request failed", and the whole retry ladder then ran against an
  endpoint that already had the event.

  The status line arrives before the body does, so by the time a read can fail the outcome is
  already decided — that is now the sixth invariant in the class javadoc, beside the five that
  each cost a duplicate before it. The limit is also declared rather than inherited:
  `WEBHOOK_MAX_RESPONSE_BODY_BYTES` (1 MiB) applies through a `WebClientCustomizer`, so it reaches
  the mTLS client too, which built its own builder and would otherwise have kept the default.

- **The worker was killed part-way through its own graceful shutdown.** Stopping it takes 30s to
  drain the Kafka containers and then, per `BoundedAsyncExecutor`, up to
  `WEBHOOK_ASYNC_SHUTDOWN_TIMEOUT_SECONDS` waiting out in-flight deliveries. There are two such
  pools and they shut down in `@PreDestroy` — after the lifecycle phase, not sharing its timeout,
  one after the other. The budget is 150s. Docker granted 35s; the Helm chart set nothing at all,
  so Kubernetes applied its default of 30s on every rollout and every HPA scale-down. Both
  SIGKILLed deliveries mid-flight, invisibly, because the ladder re-sent them later.

  `ShutdownBudgetTest` derives the budget from `application.yml` and from the number of pools
  `ExecutorConfig` actually builds, so raising the timeout — or adding a third pool — fails the
  build rather than quietly eating the margin.

- **The chart's own production configuration could not start.** `values-production.yaml` enables
  the NetworkPolicy and points PostgreSQL, Kafka and Redis at managed services, which is the
  arrangement the chart requires since it ships none of the three. The policy had no DNS rule at
  all — and an egress section denies what it does not list — so on any CNI that enforces policy
  the stack failed at name resolution. The dependency ports were then allowed only to pods in the
  same namespace, so an RDS endpoint on 5432 matched nothing either. The kind smoke test never
  saw it: kindnet does not enforce NetworkPolicy, and it stands the dependencies up in-namespace.

- **The worker validated its production config after its consumers had started.** It ran from
  `ApplicationReadyEvent`; the api moved off that trigger deliberately, because it leaves a window
  where an insecure configuration is already reachable. For the worker the window is worse — what
  is running by then is the Kafka listeners, so a worker started with a placeholder encryption key
  and the SSRF guard off delivers webhooks before the check throws. Now `@PostConstruct`, with the
  tests the worker's validator never had.

- **Production logs were never actually JSON.** `logback-spring.xml` selects `LogstashEncoder`
  under the `production` Spring profile, in both services, and nothing ever activated that
  profile: `APP_ENV=production` is an ordinary property, and `SPRING_PROFILES_ACTIVE` appeared
  nowhere in the repository. So every deployment logged plain text, promtail's `json` stage parsed
  nothing, and the `level` label the observability guide promises never reached Loki. The profile
  is now activated from `APP_ENV`, which is safe to do this way: there is no `@Profile` anywhere
  in main and no `application-<profile>.yml`, so a profile selects the appender and nothing else.

- **Three alert conditions nobody was watching, and two rule sets that had drifted.** The rules
  lived in three files — one of them, `deploy/prometheus/alerts.yml`, mounted by nobody while
  looking authoritative enough that a rule kept there appeared deployed. The two live sets were
  four rules apart, so a Kubernetes operator watched fewer conditions than a Compose one.

  Missing from all three: `outbox_oldest_pending_age_seconds`, which the observability guide names
  as the third of the three signals to alert on if you alert on nothing else, and which no other
  rule can stand in for — an Event in the outbox is not a Delivery yet, so no queue-depth metric
  counts it; `forward_oldest_pending_age_seconds`, exported all along, while the identical
  Delivery condition paged; and `up == 0` for either service, so a process that died outright
  tripped nothing directly and surfaced minutes later as a backlog someone had to interpret.

  The unmounted copy is deleted, the two survivors are identical at 22 rules, and
  `AlertRuleParityTest` keeps them that way.

- **Four things in the dashboard that the user saw and we did not.** A paid invoice always
  rendered grey — the badge compared `inv.status` against `'paid'` while `InvoiceStatus` is upper
  case — and the label was right, which is what kept it quiet. The theme toggle ignored the first
  click, because it inverted the *stored* theme and the stored theme is `system` until someone
  picks one. A 404 inside the dashboard offered the marketing site as the way back, having read a
  `localStorage` key nothing writes. And a request that never answered never settled: the axios
  client had no timeout, so a hung backend left the page spinning with no error state and nothing
  for react-query to catch.

### Removed

- **The three notification switches in Settings.** They wrote to `localStorage` and nothing ever
  read it; `Notification.requestPermission()` is called nowhere. A control that looks like a
  feature and is not costs more trust than the absent feature does. Their translation keys went
  with them.

- **`deploy/prometheus/alerts.yml`** — a third copy of the alert rules that no deployment mounted.
  See above.

### Changed

- **The member-role endpoint advertised two roles that have never existed.** Its OpenAPI
  description said "OWNER, ADMIN, MEMBER, VIEWER"; `MembershipRole` is OWNER, DEVELOPER, VIEWER,
  API_KEY, and this endpoint grants neither OWNER (409) nor API_KEY (not a human role). It was
  public — `openapi.yaml` carried it and so did the generated in-app API reference.

- **`SECURITY.md`'s supported-versions table** stopped at 2.10.x, two minors behind the release.

- **The Helm README's rollout block** claimed Flyway runs in an init container and the worker HPA
  scales on Kafka lag. The same file explains at length that the init container was removed and
  could never have worked, and `worker-hpa.yaml` scales on CPU. Replaced with what happens, and
  with the advice that block should have carried: take a backup, because `helm rollback` returns
  the images and not the schema.

- **`deploy/scripts/db-backup.sh` promised a check that did not exist** — `make
  verify-backup-parity`, absent from the Makefile and from CI. `BackupFlagParityTest` makes it
  true instead of deleting the promise: it guards `-Fc`, which makes a dump restorable at all, and
  `--no-owner --no-privileges`, which let it restore into a database whose roles differ from the
  source. That is every real recovery, and a dump taken without them looks fine until it is needed.

### Testing

- **`MigrationIndexLockingTest`** fails the build on a new migration that builds an index on an
  unbounded table without `CONCURRENTLY`, or that uses `CONCURRENTLY` without the
  `-- flyway:executeInTransaction=false` header it requires. Twenty-four shipped migrations do
  block — they cannot be fixed, since Flyway validates checksums — so the list is frozen and
  `docs/OPERATIONS.md` tells operators which upgrades need a window.

- **`src/auth` went from 5.9% to 62% covered**, 90% of branches. It is the sign-in path: the one
  place where a bug does not degrade the product but locks people out of it, and where nobody
  who hits it has a session to work around it with. The cases guard what costs the most — that
  `ProtectedRoute` treats a user whose role is missing as the *least* privileged rather than the
  most, that both `LoginPage` and `RegisterPage` put the token on the http client before asking
  who the user is, and that neither the password reset nor the invite acceptance calls a backend
  when the URL it was reached with is incomplete.

## [2.12.0] - 2026-09-07

### Fixed

- **The tunnel ingress built a regex out of the URL it was called with.** `TunnelIngressController`
  stripped the tunnel prefix with `replaceFirst("/tunnel/" + slug, "")`, and `replaceFirst` takes
  a *regex*. The slug arrives in the path of an endpoint anyone on the internet can call, so a
  slug carrying regex metacharacters strips the wrong span (a `.` matches any character), throws
  `PatternSyntaxException` out of a request handler, or lets a stranger choose a pattern that
  backtracks. The line only ever wanted "drop this prefix", which is plain string work.

  Found by the CodeQL workflow added in this release, on its first run.

- **Fourteen labels rendered as raw translation keys, and a guard that could not see them.**
  A verified account's Settings page showed the literal text `settings.emailVerified` where its
  email status belongs, because only `settings.emailUnverified` was ever added. Thirteen more of
  exactly the same shape were hiding behind it: `common.selectAll` / `deselectAll` on all four
  bulk-selection pages, `billing.nearLimit` / `overLimit`, `auditLog.noMatches` /
  `noMatchesDesc`, and `apiKeys.keyDialog.copied`.

  Every one is `t(cond ? 'a' : 'b')` with a key on one branch only, and every one shows on the
  branch nobody looks at — the verified account, the copied key, the filter that matches
  nothing. Nothing in the build could catch them: `t()` takes a `string`, so TypeScript is happy
  with any spelling; the locale-parity test compares en against uk, and a key missing from both
  is missing from neither's point of view; `eslint-plugin-i18next` asks whether a string went
  through `t()`, not whether the key resolves; and `dynamicKeys.test.ts` covers keys built by
  interpolation from a backend enum, which two plain literals in a ternary are not.

  `staticKeys.test.ts` closes it: every string literal inside a `t(...)` call has to resolve in
  `en.json`. Worth knowing — the first version of it matched `t('` directly, which finds nothing
  in `t(cond ? 'a' : 'b')`, so it passed against the very bug it was written for. It reads the
  whole argument list now. A guard that cannot fail on its motivating case is decoration.

- **A log line that lied about which number was which.** `RetryGovernor`'s AIMD-decrease warning
  was written with `{:.1f}` - Python's format syntax, not SLF4J's. SLF4J substitutes only `{}`,
  so that token printed literally, every argument after it landed one placeholder early, and the
  last one was dropped without a warning. The result read `AIMD decrease: failureRate={:.1f}%,
  batch 94.44444444444444 → 18` about a batch that was 18 and a failure rate that was 94.4%:
  the number an operator would read as the batch size was the failure rate. It is the line that
  explains why retry throughput just halved, which makes it exactly the wrong line to garble.

### Added

- **A ratchet on migration checksums.** Flyway validates the checksum of every migration it has
  applied, over the whole file including comments, so editing one that has already run makes the
  application refuse to start on every deployment that has it — and it cannot be repaired from
  the application. Nothing in the build had an opinion about that until now.

  It came up for real: the Railhook rename in this same release rewrote a comment in
  `V062__endpoint_signature_scheme.sql`. The compiler was happy, all 1666 tests passed and
  `make ratchets` was green. It was caught by reading a diff, which is not a control.

  `MigrationChecksumTest` holds a committed hash of every migration in
  `src/test/resources/db/migration-checksums.txt` and fails when one changes, is deleted or is
  renamed. Adding a migration means regenerating that file and committing it alongside:

  ```bash
  mvn test -pl railhook-api -am -Dtest=MigrationChecksumTest -Dmigrations.regenerate=true
  ```

  The two cases read differently on review, which is the point: a new migration adds a line,
  while an edit to an existing one changes a line and adds nothing.

  The hashes are not Flyway's — Flyway's are CRC32 and internal to it, and reproducing them
  would pin this test to a Flyway version for no gain, because the question is "did this file
  change", not "what number does Flyway hold". What *is* reproduced is the one thing Flyway
  ignores: the hash is taken over lines rather than bytes, so a checkout that normalises line
  endings is not a change here either. A ratchet that cries wolf gets regenerated on red
  without being read.

### Changed

- **Hookflow is now Railhook.** The name was taken on every surface that matters, twice by
  products in this same category: `hookflow.dev` serves an unrelated webhook product, the npm
  scope `@hookflow` holds eleven reserved packages describing "full-lifecycle webhook
  processing", and PyPI `hookflow` and the GitHub organisation `hookflow` belong to other
  people. `railhook` was verified free across GitHub, the npm scope, PyPI, Packagist and
  `.dev`/`.app`/`.sh` — each checked individually, because a batch of unauthenticated checks
  earns rate limits that read exactly like "available".

  Renaming buys no stars and was not done for discovery. It closes one concrete thing: the
  published names disagreed with the product and with each other. `pip install
  webhook-platform` gave you `import hookflow`; `npm i @webhook-platform/node` gave you
  `new Hookflow()`. Someone installing the SDK had to know two unrelated names, and that is
  the moment an unfamiliar project spends the trust it has. The cost of fixing it only ever
  goes up.

  The rail was already this product's own language — `AttemptRail` draws the retry ladder on a
  log scale of delay — so the name describes what the thing does rather than sitting on top of
  it.

  `UPGRADING.md` has what breaks: three new SDK package names, `import hookflow` becoming
  `import railhook`, new image and chart names, seventeen `HOOKFLOW_*` variables becoming
  `RAILHOOK_*`, and the CLI invoked as `railhook`. Three of those seventeen —
  `BIND`, `PORT` and `DOMAIN` — cross from `.env` into `docker-compose.yml`, so both
  spellings keep working there and no action is needed for them. Old images stay pullable, so a running
  deployment is untouched until it is upgraded. On Kubernetes the chart rename is an install
  beside the old release rather than an upgrade of it, because Helm derives resource names
  from the chart name.

  Deliberately **not** renamed: the Java packages `com.webhook.platform.*` and the Maven
  artifact ids. Nothing publishes them, no user sees them, and touching them would rewrite
  every import, the logging configuration and component scanning for no part of the reason
  this was done.


## [2.11.0] - 2026-09-06

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

- **`railhook admin`** — orgs, org, suspend, reinstate — and `/admin/organizations/{id}/usage`
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

- **The dashboard stopped doing SEO for somebody else's website.** `railhook.dev` is not this
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
evaluating or operating Railhook, the dashboard's `/docs` holds what you read with the product
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
  unstated: Railhook implements Standard Webhooks exactly, so a receiver already using a Svix
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
- **The reference told readers to call `http://localhost:8080`.** Railhook is
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

Railhook now speaks [Standard Webhooks](https://www.standardwebhooks.com) as well as
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
- **`railhook doctor`** re-runs the machine and configuration checks against an
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
- `SsrfProtectionCustomizer` lives once, in `railhook-common`, next to the
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
- `RetryLadder` and `RetryLadderDefaults` (`railhook-common`): one shared
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
  `mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`.
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
  `railhook.outbox` group and every rule after it included. The two are now one mapping.

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
- Helm chart (`deploy/helm/railhook`): removed the Bitnami
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
- **CLI** (`railhook-cli`) as a standalone Picocli module, published
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

[Unreleased]: https://github.com/vadymkykalo/railhook/compare/v2.20.2...HEAD
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

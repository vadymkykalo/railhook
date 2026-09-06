# Architecture

How Railhook is put together, and why it is put together that way.

For the vocabulary these diagrams use — Event, Delivery, Forward, Claim, Attempt, Deferral —
read [`CONTEXT.md`](../CONTEXT.md) first. Each term there carries a list of near-synonyms
deliberately not used, and this document holds to them: a Claim is never a lock, a Deferral is
never a failure, and the Ordering Buffer is never a queue.

Railhook carries traffic in two directions, and they are not mirror images. Outgoing, Railhook
is the sender and signs what it sends. Incoming, Railhook is the receiver and verifies what it
receives. They share one attempt lifecycle and differ everywhere else — different ladders,
different ordering guarantees, different failure semantics. Most of this document is about that
shared lifecycle, because that is where the subtlety lives.

## Contents

- [The two directions](#the-two-directions)
- [Services](#services)
- [Data model](#data-model)
- [Outgoing delivery flow](#outgoing-delivery-flow)
- [Incoming ingress flow](#incoming-ingress-flow)
- [The attempt lifecycle](#the-attempt-lifecycle)
- [Ordering](#ordering)
- [Replay is not retry](#replay-is-not-retry)
- [Tenancy](#tenancy)
- [Consistency, partitioning and failure modes](#consistency-partitioning-and-failure-modes)
- [Production topology](#production-topology)
- [CLI tunnel flow](#cli-tunnel-flow)

## The two directions

Drawn separately on purpose. One combined graph hides the fact that the two paths share only
Kafka, the worker and the attempt lifecycle — everything before and after differs.

### Outgoing — the customer's own Event travels out

```mermaid
graph LR
    App["Your Application"]
    UI["Dashboard<br/>React + Vite"]

    subgraph Railhook
        API["API Service"]
        DB[("PostgreSQL<br/>Events · Deliveries<br/>Attempts · Outbox")]
        Kafka["Kafka<br/>dispatch · 6 retry tiers · DLQ"]
        Redis[("Redis<br/>Rate limits · Ordering Buffer<br/>Circuit breaker")]
        Worker["Worker Service"]
    end

    EP1["Endpoint A"]
    EP2["Endpoint B"]

    App -->|"POST /api/v1/events"| API
    UI  -->|"REST"| API
    API -->|"Event + Deliveries + Outbox<br/>one transaction"| DB
    API -->|"announce"| Kafka
    Kafka --> Worker
    Worker -->|"Claim, load, sign"| DB
    Worker -->|"turn to send?"| Redis
    Worker -->|"POST + HMAC"| EP1
    Worker -->|"POST + HMAC"| EP2
```

### Incoming — a provider's webhook travels in

```mermaid
graph LR
    Stripe["Stripe"]
    GitHub["GitHub"]
    Shopify["Shopify"]

    subgraph Railhook
        API["API Service"]
        DB[("PostgreSQL<br/>Incoming Events<br/>Forward Attempts · Outbox")]
        Kafka["Kafka<br/>forward dispatch · retry · DLQ"]
        Worker["Worker Service"]
    end

    Svc1["Internal Service A"]
    Svc2["Internal Service B"]

    Stripe  -->|"POST /ingress/{token}"| API
    GitHub  -->|"POST /ingress/{token}"| API
    Shopify -->|"POST /ingress/{token}"| API
    API -->|"verify signature,<br/>keep as it arrived"| DB
    API -->|"announce"| Kafka
    Kafka --> Worker
    Worker -->|"Claim"| DB
    Worker -->|"POST + destination auth"| Svc1
    Worker -->|"POST + destination auth"| Svc2
```

Two asymmetries are visible here and are deliberate:

- The incoming direction has **no Ordering Buffer**. Railhook did not originate these events and
  cannot know what order the provider intended, so it does not pretend to.
- The incoming direction has a **shorter Retry Ladder**. Relaying somebody else's webhook for a
  day is not a service to anyone; the provider will usually have given up long before.

## Services

| Service | Port | Role |
|---------|------|------|
| **API** | `8080` | Event ingestion, webhook ingress, REST API, Outbox announcer |
| **Worker** | `8081` | Kafka consumer, HTTP delivery, forwarding, retry scheduling |
| **UI** | `5173` | Admin dashboard (React / Vite / shadcn/ui) |
| **PostgreSQL** | `5432` | Events, Deliveries, Incoming Events, Attempts, Outbox |
| **Kafka** | `9092` | Dispatch + 6 retry tiers + forward dispatch/retry + DLQ |
| **Redis** | `6379` | Rate limiting, FIFO ordering, circuit breaker |

## Data model

The obligations, not the whole schema — billing, workflows, alerts and the schema registry hang
off `projects` the same way and are left out so the delivery path stays readable.

Note the symmetry across the dashed line: `deliveries` is to `events` what
`incoming_forward_attempts` is to `incoming_events`. That is the Delivery/Forward pairing from
`CONTEXT.md` made concrete.

```mermaid
erDiagram
    ORGANIZATIONS ||--o{ PROJECTS : owns
    ORGANIZATIONS ||--o{ MEMBERSHIPS : has
    USERS         ||--o{ MEMBERSHIPS : joins
    PROJECTS      ||--o{ API_KEYS : authorizes

    PROJECTS      ||--o{ ENDPOINTS : registers
    ENDPOINTS     ||--o{ SUBSCRIPTIONS : "is wanted by"
    PROJECTS      ||--o{ EVENTS : announces
    EVENTS        ||--o{ DELIVERIES : "obliges one per subscription"
    SUBSCRIPTIONS ||--o{ DELIVERIES : "gave rise to"
    DELIVERIES    ||--o{ DELIVERY_ATTEMPTS : "tried via"

    PROJECTS            ||--o{ INCOMING_SOURCES : connects
    INCOMING_SOURCES    ||--o{ INCOMING_DESTINATIONS : "forwards to"
    INCOMING_SOURCES    ||--o{ INCOMING_EVENTS : received
    INCOMING_EVENTS     ||--o{ INCOMING_FORWARD_ATTEMPTS : "obliges one per destination"
    INCOMING_DESTINATIONS ||--o{ INCOMING_FORWARD_ATTEMPTS : "gave rise to"

    PROJECTS ||--o{ OUTBOX_MESSAGES : "has yet to announce"

    ORGANIZATIONS {
        uuid id PK
    }
    PROJECTS {
        uuid id PK
        uuid organization_id FK
    }
    ENDPOINTS {
        uuid id PK
        uuid project_id FK
        text url
        text secret_encrypted
        text secret_previous_encrypted "signed with too, during the rotation window"
        bool enabled "checked after the Claim, not before"
    }
    SUBSCRIPTIONS {
        uuid id PK
        uuid endpoint_id FK
        text event_type
        bool ordering_enabled
        text retry_delays "overrides the direction's Ladder"
    }
    EVENTS {
        uuid id PK
        uuid project_id FK
        text type
        jsonb payload
    }
    DELIVERIES {
        uuid id PK
        uuid event_id FK
        uuid endpoint_id FK
        uuid subscription_id FK
        text status "PENDING PROCESSING SUCCESS FAILED DLQ"
        bigint sequence_number "endpoint-scoped, stamped at creation"
        uuid claim_token "the fence"
    }
    DELIVERY_ATTEMPTS {
        uuid id PK
        uuid delivery_id FK
        int attempt_number
        int response_status
    }
    INCOMING_SOURCES {
        uuid id PK
        uuid project_id FK
        text token "the ingress path segment"
        text provider_type "GENERIC GITHUB GITLAB STRIPE SHOPIFY SLACK TWILIO"
    }
    INCOMING_EVENTS {
        uuid id PK
        uuid incoming_source_id FK
        text provider_event_id "dedupe key"
        bool verified
    }
    INCOMING_DESTINATIONS {
        uuid id PK
        uuid incoming_source_id FK
        text url
        text auth_type
        bool enabled
    }
    INCOMING_FORWARD_ATTEMPTS {
        uuid id PK
        uuid incoming_event_id FK
        uuid destination_id FK
        text status "PENDING PROCESSING SUCCESS FAILED DLQ"
        uuid replay_session_id FK
    }
    OUTBOX_MESSAGES {
        uuid id PK
        uuid project_id FK
        text status "PENDING SENDING PUBLISHED FAILED DEAD"
    }
```

Every table above except `users` carries an `organization_id` that nothing in application code
ever writes into a `WHERE` clause — see [Tenancy](#tenancy).

## Outgoing delivery flow

```mermaid
sequenceDiagram
    autonumber
    participant App as Your Application
    participant API as API Service
    participant DB as PostgreSQL
    participant K as Kafka
    participant W as Worker
    participant EP as Endpoint

    App->>API: POST /api/v1/events
    API->>DB: INSERT Event + one Delivery per matching Subscription<br/>+ Outbox row — one transaction
    API-->>App: 202 Accepted

    Note over API,DB: The Outbox row is written in the same breath as the work,<br/>so the two cannot disagree about whether it happened.

    loop every 100ms
        API->>DB: claim PENDING Outbox rows
        API->>K: produce to deliveries.dispatch
        API->>DB: mark PUBLISHED
    end

    K->>W: consume
    W->>DB: Claim the Delivery (fence token)
    W->>EP: POST payload + HMAC signature

    alt 2xx
        EP-->>W: 200
        W->>DB: SUCCESS, under the fence token
    else 408 / 429 / 5xx / timeout
        EP-->>W: 503
        W->>DB: record the Attempt, advance the Ladder
        W->>K: produce to deliveries.retry.1m
    else other 4xx
        EP-->>W: 400
        W->>DB: FAILED — retrying cannot fix a rejected request
    else Ladder exhausted
        W->>K: produce to deliveries.dlq
        W->>DB: DLQ
    end
```

The six retry topics are real Kafka topics, one per tier —
`deliveries.retry.1m`, `.5m`, `.15m`, `.1h`, `.6h`, `.24h` — not one topic with a delay header.
A tier is a topic because a consumer that sleeps holds a partition; a topic that is polled on a
schedule does not.

## Incoming ingress flow

```mermaid
sequenceDiagram
    autonumber
    participant P as Provider
    participant API as API Service
    participant DB as PostgreSQL
    participant K as Kafka
    participant W as Worker
    participant D as Destination

    P->>API: POST /ingress/{token}
    API->>DB: load the Source by token

    alt signature verification enabled
        API->>API: verify — GitHub / GitLab / Stripe / Shopify / Slack / Twilio / generic HMAC
    end

    alt signature invalid
        API->>DB: INSERT Incoming Event, verified = false
        API-->>P: 401 Unauthorized
    else valid
        API->>DB: INSERT Incoming Event (headers, body, IP, verified)<br/>+ one Forward per enabled Destination + Outbox — one transaction
        API-->>P: 202 Accepted
        API->>K: produce to incoming.forward.dispatch
        K->>W: consume
        W->>DB: Claim the Forward
        W->>D: POST body + destination auth
        alt 2xx
            W->>DB: SUCCESS
        else failure
            W->>K: produce to incoming.forward.retry
        end
    end
```

The Incoming Event is stored **before** the verdict is known, and a rejected one is stored too.
An operator debugging "the provider says it sent it" needs to see the request that failed
verification, not an absence.

## The attempt lifecycle

Both directions run the same `AttemptRunner`. Everything that differs is behind an
`AttemptStore`, of which there is one per direction. The Runner cannot read a fence token — the
Claim is a type parameter to it — which is what stops one direction's ownership rules leaking
into the other.

`AttemptRunner`'s javadoc states five invariants, each of which was once correct on one
direction and wrong on the other. Read it before changing anything here.

### Claim and fence

A Claim is exclusive ownership of one Delivery or Forward for the duration of one Attempt.
It is revocable, because the holder can die. The fence token is what makes revocation safe:
a finalisation only lands if it still matches.

```mermaid
sequenceDiagram
    autonumber
    participant W1 as Worker A
    participant DB as PostgreSQL
    participant W2 as Worker B
    participant Sweep as Stuck sweep

    W1->>DB: UPDATE … SET status=PROCESSING, claim_token=T1<br/>WHERE status=PENDING
    DB-->>W1: 1 row — Claim held
    W2->>DB: same statement
    DB-->>W2: 0 rows — already claimed, go away

    Note over W1: Worker A stops responding.

    Sweep->>DB: PROCESSING for too long → back to PENDING, token cleared
    W2->>DB: claims it, token T2

    W1->>DB: UPDATE … WHERE claim_token = T1
    DB-->>W1: 0 rows — the fence rejects the zombie
    W2->>DB: UPDATE … WHERE claim_token = T2
    DB-->>W2: 1 row — this one counts
```

Without the fence, the recovered worker's late write would overwrite an outcome that a live
worker had already recorded — the shape of every duplicate-delivery bug this design exists to
prevent.

### Admission, and what a Deferral is

Once the Claim is held, five limits are checked in a fixed order. Any of them ends the Attempt
before a request is built — and that is a **Deferral**, not a failure: nothing was tried, so
nothing is charged against the Ladder.

```mermaid
flowchart TD
    C["Claim held"] --> CB{"circuit breaker<br/>permits this endpoint?"}
    CB -- no --> D1["Deferral<br/>record the Attempt, come back in 30s"]
    CB -- yes --> TC{"tenant concurrency<br/>permit free?"}
    TC -- no --> D2["Deferral<br/>retry 2, in 60s"]
    TC -- yes --> GC{"endpoint concurrency<br/>permit free?"}
    GC -- no --> D3["Deferral<br/>release tenant permit, in 60s"]
    GC -- yes --> TR{"tenant rate limit?"}
    TR -- no --> D4["Deferral<br/>release both permits, in 30s"]
    TR -- yes --> GR{"per-endpoint rate limit?"}
    GR -- no --> D5["Deferral<br/>release both permits, in 60s"]
    GR -- yes --> S["Build body, sign, send"]

    D1 --> R["Claim released.<br/>Ladder not advanced.<br/>No Attempt consumed."]
    D2 --> R
    D3 --> R
    D4 --> R
    D5 --> R
```

The order is not arbitrary. The breaker is first because it is the only check that costs
nothing and rejects the most. Every path that takes a permit releases it, including the ones
that throw before the request exists — invariant 3.

The circuit-breaker check is the one exception to "a Deferral records nothing": it writes an
Attempt row with `CIRCUIT_BREAKER_OPEN`, because an endpoint that has gone quiet should show an
operator *why* it went quiet rather than simply stopping.

### Delivery and Forward states

Identical state sets on both sides — `PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `DLQ`.

```mermaid
stateDiagram-v2
    [*] --> PENDING : created with its Event

    PENDING --> PROCESSING : Claim taken
    PROCESSING --> PENDING : Deferral — nothing tried
    PROCESSING --> PENDING : stuck sweep revokes a lost Claim

    PROCESSING --> SUCCESS : 2xx
    PROCESSING --> FAILED : 4xx that retrying cannot fix
    PROCESSING --> PENDING : retryable — Ladder advanced, next tier scheduled

    PENDING --> DLQ : Ladder exhausted
    PENDING --> DLQ : still pending after the 96h hard cap

    DLQ --> PENDING : an operator retries it from Failed Messages

    SUCCESS --> [*]
    FAILED --> [*]
```

Three transitions carry the whole design:

- `PROCESSING → PENDING` happens for three unrelated reasons — a Deferral, a revoked Claim, and
  an ordinary retry. Only the third advances the Ladder.
- `PENDING → DLQ` has a second cause beyond the Ladder. `StaleDeliveryEscalationService` sweeps
  anything still `PENDING` past a hard cap (default 96h, comfortably past the default Ladder's
  ~83h worst case) so a long-degraded endpoint cannot grow the backlog without bound. It also
  exports `delivery_oldest_pending_age_seconds`, which is the gauge to alert on.
- `DLQ → PENDING` is a human decision. The DLQ is where an obligation is abandoned by Railhook
  and kept for a person to decide about — the UI calls it **Failed Messages** on purpose,
  because "DLQ" is vocabulary you have to already know.

### The two ladders

Declared once, in `RetryLadderDefaults`. There is no fallback ladder anywhere — a Subscription
or Destination may override the delays and the attempt count, and nothing else may.

| | Outgoing | Incoming |
|---|---|---|
| Delays | 1m · 5m · 15m · 1h · 6h · 24h | 1m · 5m · 15m · 1h · 6h |
| Attempts | 7 | 6 |
| Reaches | ~24h after the last failure | ~6h after the last failure |

```mermaid
flowchart LR
    subgraph O["Outgoing — 7 attempts, reaching ~24h"]
        direction LR
        O1["try 1<br/>now"] -->|"1m"| O2["try 2"] -->|"5m"| O3["try 3"] -->|"15m"| O4["try 4"] -->|"1h"| O5["try 5"] -->|"6h"| O6["try 6"] -->|"24h"| O7["try 7"] --> OD(["Failed Messages"])
    end
    subgraph I["Incoming — 6 attempts, reaching ~6h"]
        direction LR
        I1["try 1<br/>now"] -->|"1m"| I2["try 2"] -->|"5m"| I3["try 3"] -->|"15m"| I4["try 4"] -->|"1h"| I5["try 5"] -->|"6h"| I6["try 6"] --> ID(["Failed Messages"])
    end
```

They differ on purpose. Do not "fix" that into agreement.

Separately from the Ladder, `RetryPolicy` computes an exponential backoff with 25% jitter. That
is used **only** for rescheduling a Deferral, never for a failed Attempt. Conflating the two is
how a deferred delivery ends up consuming its Ladder.

## Ordering

Outgoing only, opt-in per Subscription. Every Delivery to an endpoint is stamped with an
endpoint-scoped Sequence Number at creation; a Delivery whose predecessors have not resolved
waits in the Ordering Buffer.

```mermaid
sequenceDiagram
    autonumber
    participant W as Worker
    participant OB as Ordering Buffer (Redis)
    participant DB as PostgreSQL
    participant EP as Endpoint

    Note over OB: last delivered to this endpoint = 41

    W->>OB: may Delivery 42 go?
    OB-->>W: yes
    W->>EP: POST
    EP-->>W: 200
    W->>OB: advance to 42, release what was waiting on it

    W->>OB: may Delivery 44 go?
    OB-->>W: no — 43 has not resolved
    Note over W,OB: The Gap is the whole range 43..43, not just "the one before".<br/>Checking only n-1 let 44 sail through whenever 43 was already terminal.
    W->>DB: park — Claim released, token cleared, back to the Ladder

    alt 43 resolves
        OB-->>W: 44 may go
    else 43 never resolves
        Note over OB: gap timeout fires, counter incremented,<br/>44 proceeds rather than blocking the endpoint forever
    end
```

Parking hands the row back to the Ladder, so the Claim is genuinely over and the fence token is
cleared rather than left stale for a later writer to match. How fast a parked burst drains is
governed by the retry scheduler's poll cadence, not by the buffer's own delay.

## Replay is not retry

Both words describe getting an Event to an endpoint a second time, and they are different
operations with different failure modes.

```mermaid
flowchart LR
    subgraph Retry["Retry — the same obligation"]
        D1["Delivery #7<br/>sequence 42"] --> A1["Attempt 1 — 503"]
        A1 --> A2["Attempt 2 — 503"]
        A2 --> A3["Attempt 3 — 200"]
        A3 --> S1["Delivery #7 = SUCCESS"]
    end

    subgraph Replay["Replay — a new obligation"]
        E["Event, already stored"] --> D2["Delivery #7<br/>sequence 42 — DLQ"]
        E --> D3["Delivery #91<br/>sequence 58, fresh"]
        D3 --> A4["Attempt 1 — 200"]
    end
```

A retry is the next Attempt on the *same* Delivery, and it advances that Delivery's Ladder.
A replay builds a **fresh** Delivery from an Event already in the store, with the same content
and a new Sequence Number, and leaves the original where it is. The UI calls replay
**Time Machine**; `ReplaySession` records the batch so it can be estimated, watched and
cancelled.

The new Sequence Number is the part that matters: a replayed Delivery takes its place at the
*end* of the endpoint's order, not back at position 42 where it would block everything since.

## Tenancy

Everything a customer owns hangs off exactly one Organization. That scoping is not enforced by
application code and is not reviewable in application code — it is a Hibernate `@TenantId` on
~35 entities, resolved per request.

```mermaid
flowchart TD
    R["HTTP request"] --> A["Authenticate<br/>JWT session · API key · platform admin"]
    A --> B["Bind the Organization to the thread"]
    B --> C["Any repository call"]
    C --> H["Hibernate appends<br/>organization_id = ?"]
    H --> Q[("PostgreSQL")]

    B -.->|"no Organization bound"| X["Throws.<br/>The only sanctioned exception is<br/>TenantContext.runAsSystem"]

    C --> N["findById included —<br/>a guessed UUID from another org<br/>returns empty, not a 403"]
```

The consequence is a rule the build enforces: **never hand-roll an org check.** A service method
that takes an `organizationId` parameter fails the build, because it is either redundant with
the `@TenantId` or it is a second, weaker mechanism that will eventually disagree with it.

What `@TenantId` does *not* cover is what the ratchets exist for: work with no request behind it
needs a scope entered explicitly and outside the transaction, native queries bypass the filter,
and your own thread pool does not inherit the binding.

## Consistency, partitioning and failure modes

### What is guaranteed

**Delivery is at-least-once, never exactly-once.** An Attempt can succeed at the endpoint and
fail to record — the response arrives, the process dies before the `SUCCESS` write lands, the
stuck sweep hands the obligation to another worker, and the endpoint sees the Event twice. This
is not a defect to be engineered away; it is the honest cost of not running a transaction across
HTTP. It is why every Event carries a stable id, why the Standard Webhooks `webhook-id` header
is the Delivery id and does not change between Attempts, and why receivers are told to dedupe on
it.

**The Outbox makes acceptance and announcement agree.** The Event, its Deliveries and the Outbox
row are one transaction. Either the customer got a 202 and the work will be announced, or they
got an error and none of it exists. A separate announcer polls the Outbox — so Kafka being down
delays delivery and never loses an accepted Event.

**Ordering is per endpoint, opt-in, and outgoing only.** With `ordering_enabled` off — the
default — Deliveries to one endpoint may overtake each other freely, which is what makes the
throughput.

### Partitioning

Kafka messages are keyed so that all work for one endpoint lands on one partition, which is what
lets the Ordering Buffer be a cheap Redis check rather than a distributed sort. The cost is the
usual one: a single very busy endpoint is bounded by one partition's consumer, and adding
partitions rebalances that boundary without removing it. `delivery_attempts` and
`tunnel_request_log` are partitioned in Postgres too, by time, which is what makes retention a
detach rather than a delete.

### Failure modes worth knowing

| What breaks | What happens | Where to look |
|---|---|---|
| **Redis unreachable** | The circuit breaker **fails open** — calls are permitted rather than blocked, because refusing every delivery is worse than losing a safety net. It is counted, not silent. | `circuit_breaker_degraded_total` |
| **An endpoint is degraded for days** | The Ladder runs out, and anything still `PENDING` past the hard cap is escalated to Failed Messages. Backlog is bounded. | `delivery_oldest_pending_age_seconds` |
| **Retry storm after a mass outage** | `RetryGovernor` applies AIMD congestion control to the scheduler's batch size, plus a queue-depth admission gate and a consecutive-failure cooldown. | governor gauges |
| **A worker dies mid-Attempt** | The Claim is revoked by the stuck sweep and the fence token stops the zombie's late write. The endpoint may see a duplicate. | see at-least-once, above |
| **Kafka consumer lag** | Deliveries are late, not lost — the Outbox already recorded them. | consumer lag dashboard |
| **A transformation template breaks** | Treated as **retryable**, and the raw payload is never sent in its place. A template fixed within the Ladder still gets the Event out. | invariant 4 |
| **Postgres restored from backup** | Postgres, Kafka and Redis can disagree about what has been delivered. **There is no written reconciliation procedure for this** — see `OPERATIONS.md`. | known limitation |

### Scaling limits

API and worker are stateless and scale horizontally; both have an HPA in the chart. The binding
constraints, in the order they are usually hit: Postgres write throughput on
`delivery_attempts`, partition count for a single hot endpoint, and Redis round-trips per Attempt
on the ordering path.

## Production topology

```mermaid
flowchart TB
    Ingress["Ingress<br/>TLS termination"]

    subgraph K8s["Kubernetes namespace"]
        UISvc["ui Service"] --> UIPods["ui Deployment<br/>nginx + static bundle"]
        APISvc["api Service"] --> APIPods["api Deployment<br/>HPA · PDB"]
        WorkerPods["worker Deployment<br/>HPA · PDB"]
        Backup["db-backup CronJob"]
        Topics["kafka-topics Job<br/>runs once on install"]
        NP["NetworkPolicy"]
    end

    subgraph Data["Data services — external by default"]
        PG[("PostgreSQL")]
        KafkaC["Kafka"]
        RedisC[("Redis")]
    end

    subgraph Obs["Observability"]
        SM["ServiceMonitor"]
        PR["PrometheusRule"]
        Graf["Grafana dashboards<br/>shipped in the chart"]
    end

    Ingress --> UISvc
    Ingress --> APISvc
    APIPods --> PG
    APIPods --> KafkaC
    APIPods --> RedisC
    WorkerPods --> PG
    WorkerPods --> KafkaC
    WorkerPods --> RedisC
    Topics --> KafkaC
    Backup --> PG
    SM -.->|"scrapes :8080 and :8081"| APIPods
    SM -.-> WorkerPods
    PR -.-> SM
    Graf -.-> SM
```

The chart ships no database. Postgres, Kafka and Redis are configured as external services
because an operator who is going to run this in production already has opinions about all three,
and a bundled subchart mostly serves to make the first `helm install` look easy and the first
upgrade look impossible.

## CLI tunnel flow

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Developer (localhost)
    participant CLI as Railhook CLI
    participant API as API Service
    participant WS as WebSocket Hub
    participant P as Provider

    Dev->>CLI: railhook listen 3000
    CLI->>API: POST /api/v1/tunnels
    API-->>CLI: 201 {slug, wsUrl}
    CLI->>WS: connect WSS /ws/tunnel
    WS-->>CLI: connected

    Note over CLI,WS: Public URL live for as long as the CLI stays connected.

    P->>API: POST /tunnel/{slug}
    API->>WS: forward the request
    WS->>CLI: TunnelRequestMessage
    CLI->>Dev: POST http://localhost:3000
    Dev-->>CLI: 200 + body
    CLI->>WS: TunnelResponseMessage
    WS->>API: response
    API-->>P: 200

    Note over CLI: Auto-reconnect with exponential backoff, capped at 2min.
```

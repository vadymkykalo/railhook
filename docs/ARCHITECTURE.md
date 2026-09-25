# Architecture

Terms (Event, Delivery, Forward, Claim, Attempt, Deferral) are defined in
[`CONTEXT.md`](../CONTEXT.md).

Railhook moves traffic in two directions. Outgoing, it sends the customer's Events and signs
them. Incoming, it receives providers' webhooks and verifies them. Both use one attempt
lifecycle; ladders, ordering and failure handling differ.

## The two directions

### Outgoing: the customer's own Event travels out

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

### Incoming: a provider's webhook travels in

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

Incoming has no Ordering Buffer (Railhook cannot know the provider's intended order) and a
shorter Retry Ladder.

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

Delivery-path tables only. `deliveries` is to `events` what `incoming_forward_attempts` is to
`incoming_events`.

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

Every table except `users` has an `organization_id`, applied by Hibernate, never written by hand.
See [Tenancy](#tenancy).

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
    API->>DB: INSERT Event + one Delivery per matching Subscription<br/>+ Outbox row, one transaction
    API-->>App: 201 Created

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
        W->>DB: FAILED, retrying cannot fix a rejected request
    else Ladder exhausted
        W->>K: produce to deliveries.dlq
        W->>DB: DLQ
    end
```

Each retry tier is its own Kafka topic (`deliveries.retry.1m`, `.5m`, `.15m`, `.1h`, `.6h`,
`.24h`), because a sleeping consumer would hold a partition.

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
        API->>API: verify, GitHub / GitLab / Stripe / Shopify / Slack / Twilio / generic HMAC
    end

    alt signature invalid
        API->>DB: INSERT Incoming Event, verified = false
        API-->>P: 401 Unauthorized
    else valid
        API->>DB: INSERT Incoming Event (headers, body, IP, verified)<br/>+ one Forward per enabled Destination + Outbox, one transaction
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

The Incoming Event is stored before verification, and kept if it fails, so an operator can see
the rejected request.

## The attempt lifecycle

Both directions run `AttemptRunner`, with one `AttemptStore` per direction. The Claim is a type
parameter, so the Runner cannot read a fence token. Read the Runner's javadoc (five invariants)
before changing anything here.

### Claim and fence

A Claim is exclusive, revocable ownership of one Delivery or Forward for one Attempt. A write
only lands if the fence token still matches, so a worker that lost its Claim cannot overwrite the
outcome.

```mermaid
sequenceDiagram
    autonumber
    participant W1 as Worker A
    participant DB as PostgreSQL
    participant W2 as Worker B
    participant Sweep as Stuck sweep

    W1->>DB: UPDATE … SET status=PROCESSING, claim_token=T1<br/>WHERE status=PENDING
    DB-->>W1: 1 row, Claim held
    W2->>DB: same statement
    DB-->>W2: 0 rows, already claimed, go away

    Note over W1: Worker A stops responding.

    Sweep->>DB: PROCESSING for too long → back to PENDING, token cleared
    W2->>DB: claims it, token T2

    W1->>DB: UPDATE … WHERE claim_token = T1
    DB-->>W1: 0 rows, the fence rejects the zombie
    W2->>DB: UPDATE … WHERE claim_token = T2
    DB-->>W2: 1 row, this one counts
```

### Admission, and what a Deferral is

After the Claim, five limits are checked in order. Failing any of them is a **Deferral**: nothing
was sent, so the Ladder does not advance.

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

Every path that takes a permit releases it. The breaker is the one Deferral that records an
Attempt (`CIRCUIT_BREAKER_OPEN`), so an operator sees why the endpoint went quiet.

### Delivery and Forward states

Same states on both sides: `PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `DLQ`.

```mermaid
stateDiagram-v2
    [*] --> PENDING : created with its Event

    PENDING --> PROCESSING : Claim taken
    PROCESSING --> PENDING : Deferral, nothing tried
    PROCESSING --> PENDING : stuck sweep revokes a lost Claim

    PROCESSING --> SUCCESS : 2xx
    PROCESSING --> FAILED : 4xx that retrying cannot fix
    PROCESSING --> PENDING : retryable, Ladder advanced, next tier scheduled

    PENDING --> DLQ : Ladder exhausted
    PENDING --> DLQ : still pending after the 96h hard cap

    DLQ --> PENDING : an operator retries it from Failed Messages

    SUCCESS --> [*]
    FAILED --> [*]
```

- `PROCESSING -> PENDING` has three causes: a Deferral, a revoked Claim, a retry. Only a retry
  advances the Ladder.
- `PENDING -> DLQ`: the Ladder ran out, or `StaleDeliveryEscalationService` hit the hard cap
  (default 96h, above the ladder's ~83h worst case). Alert on
  `delivery_oldest_pending_age_seconds`.
- `PENDING -> DLQ` also happens when Railhook auto-disables a target after a window of only
  failures (`EndpointAutoDisableService`, fed by `AttemptStore#recordTargetOutcome`). If the
  owner disabled the target, its queued work ends `FAILED` instead.
- `DLQ -> PENDING` is a human decision, from **Failed Messages** in the UI.

### The two ladders

Declared once, in `RetryLadderDefaults`. No fallback ladder. A Subscription or Destination may
override delays, attempt count and retryable statuses (`RetryableStatuses`, default
`408,429,500-599`).

`Retry-After` on a 429 or 503 can push one Attempt later, never earlier, capped by
`WEBHOOK_RETRY_AFTER_MAX_SECONDS` (6h).

| | Outgoing | Incoming |
|---|---|---|
| Delays | 1m · 5m · 15m · 1h · 6h · 24h | 1m · 5m · 15m · 1h · 6h |
| Attempts | 7 | 5 |
| Waits used | all six, ~31h21m first to last | the first four, ~1h21m first to last; the 6h tier is reached only if a Destination raises its attempts |

```mermaid
flowchart LR
    subgraph O["Outgoing, 7 attempts, reaching ~24h"]
        direction LR
        O1["try 1<br/>now"] -->|"1m"| O2["try 2"] -->|"5m"| O3["try 3"] -->|"15m"| O4["try 4"] -->|"1h"| O5["try 5"] -->|"6h"| O6["try 6"] -->|"24h"| O7["try 7"] --> OD(["Failed Messages"])
    end
    subgraph I["Incoming, 5 attempts, reaching ~1h"]
        direction LR
        I1["try 1<br/>now"] -->|"1m"| I2["try 2"] -->|"5m"| I3["try 3"] -->|"15m"| I4["try 4"] -->|"1h"| I5["try 5"] --> ID(["Failed Messages"])
    end
```

They differ on purpose. Do not make them agree.

`RetryPolicy` (exponential backoff, 25% jitter) is only for rescheduling a Deferral, never a
failed Attempt.

## Ordering

Outgoing only, opt-in per Subscription. Each Delivery gets an endpoint-scoped Sequence Number at
creation. A Delivery whose predecessors have not resolved waits in the Ordering Buffer.

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
    OB-->>W: no, 43 has not resolved
    Note over W,OB: The Gap is the whole range 43..43, not just "the one before".<br/>Checking only n-1 let 44 sail through whenever 43 was already terminal.
    W->>DB: park, Claim released, token cleared, back to the Ladder

    alt 43 resolves
        OB-->>W: 44 may go
    else 43 never resolves
        Note over OB: gap timeout fires, counter incremented,<br/>44 proceeds rather than blocking the endpoint forever
    end
```

Parking releases the Claim and clears the fence token. Drain speed of a parked burst depends on
the retry scheduler's poll cadence, not the buffer's delay.

## Replay is not retry

```mermaid
flowchart LR
    subgraph Retry["Retry, the same obligation"]
        D1["Delivery #7<br/>sequence 42"] --> A1["Attempt 1, 503"]
        A1 --> A2["Attempt 2, 503"]
        A2 --> A3["Attempt 3, 200"]
        A3 --> S1["Delivery #7 = SUCCESS"]
    end

    subgraph Replay["Replay, a new obligation"]
        E["Event, already stored"] --> D2["Delivery #7<br/>sequence 42, DLQ"]
        E --> D3["Delivery #91<br/>sequence 58, fresh"]
        D3 --> A4["Attempt 1, 200"]
    end
```

A retry is the next Attempt on the same Delivery and advances its Ladder. A replay (UI: **Time
Machine**, recorded as a `ReplaySession`) creates a new Delivery from the stored Event with a new
Sequence Number, so it goes to the end of the endpoint's order.

## Tenancy

Scoping to an Organization is a Hibernate `@TenantId` on about 35 entities, resolved per request.

```mermaid
flowchart TD
    R["HTTP request"] --> A["Authenticate<br/>JWT session · API key · platform admin"]
    A --> B["Bind the Organization to the thread"]
    B --> C["Any repository call"]
    C --> H["Hibernate appends<br/>organization_id = ?"]
    H --> Q[("PostgreSQL")]

    B -.->|"no Organization bound"| X["Throws.<br/>The only sanctioned exception is<br/>TenantContext.runAsSystem"]

    C --> N["findById included -<br/>a guessed UUID from another org<br/>returns empty, not a 403"]
```

**Never hand-roll an org check.** A service method taking an `organizationId` fails the build.
`@TenantId` does not cover work without a request (enter a scope explicitly, outside the
transaction), native queries, or your own thread pools. The ratchets check these.

## Consistency, partitioning and failure modes

### What is guaranteed

- **At-least-once delivery.** An Attempt can succeed at the endpoint and fail to record, so the
  endpoint may see it twice. The `webhook-id` header is the Delivery id and stays the same across
  Attempts; receivers dedupe on it.
- **The Outbox makes acceptance and announcement agree.** Event, Deliveries and Outbox row are
  one transaction. If Kafka is down, delivery is late, not lost.
- **Ordering is per endpoint, opt-in, outgoing only.** Off by default.

### Partitioning

Kafka messages are keyed by endpoint, so one hot endpoint is limited to one consumer.
`delivery_attempts` and `tunnel_request_log` are time-partitioned in Postgres, so retention is a
partition detach.

### Failure modes worth knowing

| What breaks | What happens | Where to look |
|---|---|---|
| **Redis unreachable** | The circuit breaker fails open: calls are allowed and counted. | `circuit_breaker_degraded_total` |
| **An endpoint is degraded for days** | The Ladder runs out; anything `PENDING` past the hard cap goes to Failed Messages. | `delivery_oldest_pending_age_seconds` |
| **Retry storm after a mass outage** | `RetryGovernor` applies AIMD to the scheduler batch size, a queue-depth gate and a failure cooldown. | governor gauges |
| **A worker dies mid-Attempt** | The stuck sweep revokes the Claim; the fence blocks the late write. The endpoint may see a duplicate. | at-least-once, above |
| **Kafka consumer lag** | Deliveries are late, not lost. | consumer lag dashboard |
| **A transformation template breaks** | Retryable; the raw payload is never sent instead. | invariant 4 |
| **Postgres restored from backup** | Postgres, Kafka and Redis disagree. Flush Redis, leave Kafka, let the stuck sweep re-queue. | [OPERATIONS.md](./OPERATIONS.md#disaster-recovery) |

### Scaling limits

API and worker are stateless and have an HPA in the chart. Limits, in the order usually hit:
Postgres writes on `delivery_attempts`, partition count for one hot endpoint, Redis round-trips
per Attempt on the ordering path.

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

    subgraph Data["Data services, external by default"]
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
    SM -.->|"scrapes :8082 and :8081"| APIPods
    SM -.-> WorkerPods
    PR -.-> SM
    Graf -.-> SM
```

The chart ships no database. Postgres, Kafka and Redis are external.

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

# Observability

What Railhook exports, what each number means, and which of them are worth waking someone up
for. The vocabulary here is `CONTEXT.md`'s — Delivery, Forward, Attempt, Claim, Deferral,
Retry Ladder, DLQ.

## Where the metrics are

Prometheus scrapes `/actuator/prometheus` on a **management port that is not the application
port**:

| Service | App port | Management port |
|---|---|---|
| API | `8080` | `8082` |
| Worker | (no public app port) | `8081` |

The split is deliberate and has bitten before. The app's own `/actuator/**` requires a JWT or an
API key; Prometheus has neither. Scraping the app port returns 401, every `PrometheusRule` fires
on no data, and the resulting alert storm says nothing about the platform. If you are seeing
alerts with no data, check that the management port is the one being scraped, and that
`management.server.address` is not bound to `127.0.0.1` — an actuator that answers only inside
the pod is invisible to Kubernetes too.

In Kubernetes the chart wires this for you: `servicemonitor.yaml` targets the named `management`
port, and `prometheusrule.yaml` ships the alert rules. Locally, `monitoring/` has a full
Prometheus + Alertmanager + Loki + Promtail + Grafana stack.

Four Grafana dashboards ship in `deploy/helm/railhook/dashboards/`:
`railhook-overview.json`, `railhook-worker.json`, `jvm-micrometer.json`, `kafka-consumer.json`.

## The numbers that actually matter

Railhook exports well over a hundred series. These are the ones that tell you whether the
platform is healthy, grouped by the question they answer.

### Is work getting through?

| Metric | Reading it |
|---|---|
| `events_ingested_total` | Accepted Events. The top of the funnel. |
| `deliveries_created_total` | Obligations produced by fan-out. Divided by the above, this is your average subscriptions-per-event. |
| `webhook_delivery_attempts_total` | Attempts, tagged by outcome. Attempts far above Deliveries means the Ladder is doing a lot of work. |
| `webhook_delivery_latency_ms` | Time in the endpoint's hands. A rising p95 here is the endpoint's problem, not Railhook's. |
| `incoming_events_received_total` / `incoming_forward_attempts_total` | The same pair for the incoming direction. |

### Is anything piling up?

| Metric | Reading it |
|---|---|
| `delivery_queue_depth`, `incoming_forward_queue_depth` | Obligations waiting. Steady is fine; monotonically rising is not. |
| `outbox_queue_depth`, `outbox_oldest_pending_age_seconds` | The announcer's backlog. A rising age here means Kafka is unreachable and accepted Events are not yet announced — they are safe, but they are not moving. |
| **`delivery_oldest_pending_age_seconds`** | **The single best health signal.** The age of the oldest unresolved obligation. It absorbs every cause — a dead endpoint, a broker outage, a stuck Claim — into one number with an obvious meaning. `forward_oldest_pending_age_seconds` is its incoming twin. |
| `webhook_dlq_depth`, `incoming_forward_dlq_depth` | Abandoned obligations awaiting a human. |
| `delivery_escalated_to_dlq_total` | Obligations the 96h hard cap gave up on, as opposed to ones that exhausted the Ladder normally. A non-zero rate here means something was degraded for days. |

### Is a limiter or a breaker interfering?

Every one of these describes a **Deferral** — nothing was tried, so nothing was charged against
the Ladder. A high rate is not an error; it is the platform protecting something.

| Metric | Reading it |
|---|---|
| `circuit_breaker_rejected_total` | Attempts the breaker refused. |
| `circuit_breaker_state_transitions_total`, `circuit_breaker_slow_trips_total` | How often, and whether it tripped on failures or on slowness. |
| **`circuit_breaker_degraded_total`** | Redis was unreachable and the breaker **failed open** — calls were permitted with no protection. Non-zero means you are running without a safety net, which is exactly the sort of thing that is otherwise silent. |
| `webhook_concurrency_rejected_total`, `webhook_rate_limit_exceeded_total`, `webhook_project_rate_limit_exceeded_total` | Which of the four limits is biting. |
| `*_fallback_total` (rate limit, concurrency, quota) | The limiter could not reach Redis and fell back. Same class of signal as `degraded_total`: a guard that is not guarding. |

### Is the retry machinery coping?

| Metric | Reading it |
|---|---|
| `retry_governor_effective_batch` | AIMD congestion control on the scheduler's batch size. Collapsing toward its floor means the governor is throttling itself against a struggling downstream. |
| `retry_governor_pending_count`, `retry_governor_consecutive_failures`, `retry_governor_cooldown_remaining` | Why it is throttling. |
| `webhook_ordering_buffered_total` | Deliveries waiting their turn in the Ordering Buffer. |
| **`webhook_ordering_gap_timeout_total`** | A Gap never closed and the buffer let the next Delivery past to avoid blocking the endpoint forever. **This is an ordering guarantee being consciously broken** — rare is expected, frequent means something upstream is not resolving. |
| `webhook_sequence_desync_total`, `webhook_sequence_reseeded_total` | The Redis cursor and Postgres disagreed about position. |
| `transform_failed_total` | A transformation template failed. The raw payload was not sent in its place. |

### Is the schema and rules layer doing anything?

`schema_validation_failures_total`, `schema_compatibility_rejected_total`,
`rules_matched_total`, `rules_drop_total`, `events_duplicate_total` (idempotency working),
`events_fanout_limited_total` (an Event matched more subscriptions than the fan-out cap allows).

## Alerts

The same set ships twice, because a deployment reads one or the other and never both:
`monitoring/prometheus/alerts.yml` for Compose, and the PrometheusRule the chart renders from
`deploy/helm/railhook/templates/prometheusrule.yaml` for Kubernetes. They are held identical by
`AlertRuleParityTest`, a ratchet — they had drifted before it existed, and a Kubernetes operator
was watching four fewer conditions than a Compose one without either knowing.

Grouped by what they mean rather than by what they measure:

**Work was accepted but never announced** — `OutboxOldestPendingAgeHigh`, `OutboxSendingStuck`.
The earliest thing that can go wrong, and the only one invisible to every other rule here: an
Event sitting in the outbox is not a Delivery yet, so no queue-depth metric counts it.

**The backlog is growing** — `DeliveryPendingBacklogGrowing`, `…High`, `…Critical`,
`IncomingForwardPendingBacklogHigh`, `OldestPendingDeliveryStale`,
`OldestPendingDeliveryCritical`, `OldestPendingForwardStale`. Severities on depth plus alerts on
age, because depth and age fail differently: a big steady backlog that drains is fine, and a
small backlog whose oldest member is four days old is not.

**Obligations are being abandoned** — `DlqDepthGrowing`, `DlqRateHigh`,
`IncomingForwardFailureRateHigh`, `DlqActionableBacklog`,
`IncomingForwardDlqActionableBacklog`. The last two are the ones that need a person: they count
what is sitting in DLQ status waiting to be retried or purged, and they return to zero when
someone does it.

**A protection is engaging, or has stopped protecting** — `CircuitBreakerTripsHigh`,
`CircuitBreakerRejectionsHigh`, and `CircuitBreakerDegraded`. Note the third is the odd one out:
the first two mean the breaker is working, the third means it is **not** because Redis is gone.

**The platform itself is unwell** — `RetryGovernorCooldown`,
`RetryGovernorConsecutiveFailures`, `ApiErrorRateHigh`.

**It is not running at all** — `ApiDown`, `WorkerDown`, on `up == 0`. Everything above measures
what the platform is doing; these two say whether it is doing anything. Without them a process
that died outright tripped nothing directly and surfaced minutes later as a backlog somebody had
to interpret.

## If you only alert on three things

1. `delivery_oldest_pending_age_seconds` above a few hours. Catches nearly everything.
2. `circuit_breaker_degraded_total` increasing. Catches the guards being down.
3. `outbox_oldest_pending_age_seconds` above a few minutes. Catches accepted-but-unannounced
   work, which no per-endpoint metric will show you.

## Tracing

There is none. Prometheus coverage is thorough, but there is no OpenTelemetry export, so a slow
delivery cannot be followed across ingest → Kafka → Attempt as a single trace. Correlation ids
exist throughout and would carry it; see `ROADMAP.md`.

## Logs

`monitoring/` ships Promtail → Loki → Grafana. The worker logs every Attempt outcome with the
Delivery id, so `delivery_id` is the join key between a Grafana panel and the Deliveries page in
the dashboard.

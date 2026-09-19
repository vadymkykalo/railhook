---
title: The transactional outbox, or how Railhook never loses an event it has accepted
lead: Your service gets a 201, and the pod that sent it is OOM-killed four milliseconds later. Whether that event still reaches every endpoint is decided by one Postgres transaction, and everything after it is built to assume the message arrives twice.
description: "The transactional outbox pattern with Postgres, Kafka and Spring Boot: why dual writes lose events, and how Railhook never loses an accepted webhook."
date: 2026-09-20
author: Vadym Kykalo
tags: [outbox, kafka, postgres, reliability, spring-boot]
sourcesCheckedOn: 2026-09-19
---

Your checkout service sends `order.paid` to Railhook and gets `201 Created` back. Four milliseconds
later the API pod that answered is OOM-killed. The event must still reach all five endpoints
subscribed to it, possibly over the next 31 hours, and your service has already thrown its copy away.

Here is the position this post defends: **a delivery guarantee can only start inside a database
transaction.** Kafka, workers and retry ladders carry the guarantee; none of them can create it. So
the first thing Railhook does with an event is make it and its announcement one commit, and
everything downstream is written on the assumption that every message may arrive twice.

The vocabulary is the codebase's own. An **Event** is what your system announced. A **Delivery** is
the obligation to get one Event to one endpoint. An **Attempt** is one HTTP request towards that.

## What can "never lost" actually promise?

Three delivery semantics, and it is worth being precise about each, because vendors blur them.

- **At-most-once.** Send, never resend. If the request is lost, the event is lost. Nothing is ever
  processed twice.
- **At-least-once.** Resend until the receiver acknowledges. Nothing is lost, but if the receiver
  processed the request and its response was lost, the resend is processed again.
- **Exactly-once.** Every event processed once and only once.

The third is not available over HTTP to a server you do not control, and the reason fits in one
sentence: when a request times out, the sender cannot tell whether the request was lost or the
response was. Those two failures need opposite reactions (resend, or do not) and they look
identical from the sending side. No retry policy, however clever, can choose correctly without
information only the receiver has.

:::figure delivery-semantics

Kafka's own documentation draws the same line. Inside Kafka, exactly-once is achievable with
transactions, but "exactly-once delivery for other destination systems generally requires
cooperation with such systems"
([Apache Kafka, *Message Delivery Semantics*](https://kafka.apache.org/41/design/design/#message-delivery-semantics)).

So Railhook promises exactly what can be kept: **at-least-once delivery end to end, and an
exactly-once effect for a receiver that deduplicates on `webhook-id`.** The rest of this post is how
each step keeps the first half and narrows the duplicates that the second half has to absorb.

**Takeaway:** anyone promising exactly-once delivery to your HTTP endpoint is either counting on
your deduplication or not counting.

## Why can't the API just write to Postgres and publish to Kafka?

Because those are two systems with no transaction spanning them, and every ordering of the two
writes has a hole.

**Commit, then publish.** Insert the Event, commit, answer `201`, publish. A crash between the commit
and the publish (a deploy, an OOM kill, a node going away) leaves the Event in the database, the
client told it was accepted, and no worker that will ever hear of it. Nothing errors. The first
symptom is a customer asking where it went.

**Publish, then commit.** Now the failure runs the other way: the publish succeeds, the commit fails,
and a worker receives a message about a row that does not exist. The client got a `500` and retries,
and you have a second message for a second row. Publishing *inside* the transaction, before the
commit, is the same ordering with better manners. The broker does not roll back when Postgres does.

:::figure dual-write

**Takeaway:** at enough volume a crash lands between any two calls you have. Design as if it
already did.

## What does the outbox change?

It turns the announcement from a network call into a row, and a row can share a transaction with
the Event. [microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)
puts it as storing the message "in the database as part of the transaction that updates the
business entities", with a separate process sending it on to the broker.

In Railhook, the whole ingest runs in one `TransactionTemplate`:

```java
response = transactionTemplate.execute(status ->
        doIngestEvent(projectId, request, idempotencyKey, pendingSequenceAssignment,
                organizationToCharge));
```

and inside it, after the Event and one Delivery per matching Subscription are saved, one Outbox row
is written per Delivery:

```java
List<OutboxMessage> outboxMessages = new ArrayList<>(savedDeliveries.size());
for (Delivery delivery : savedDeliveries) {
    outboxMessages.add(deliveryDispatch.outboxFor(delivery, projectId, DeliveryDispatch.Reason.CREATED));
}
outboxMessageRepository.saveAll(outboxMessages);
```

(`railhook-api/.../service/EventIngestService.java`)

Either all of it commits and the client gets `201`, or none of it exists. There is no state in which
the Event is stored and its announcement is not.

The client's own POST is a dual write too, seen from its side: after a timeout it cannot know
whether the Event was stored. That is what `Idempotency-Key` is for. `events` has a unique index on
`(project_id, idempotency_key)`, so a retried POST gets back the Event it already created. Two
concurrent retries that both miss the lookup collide on the index, and the loser returns the
winner's Event. Incoming webhooks get the same treatment keyed on the provider's own id
(`X-GitHub-Delivery`, `X-Shopify-Webhook-Id`, Stripe's `evt_…`), with a unique index on
`(incoming_source_id, provider_event_id)`.

Not every second write earns an outbox. The monthly quota counter lives in Redis and is charged
*after* the commit, best-effort on purpose: the code's own comment says failing to charge is better
than failing an ingest that has already been accepted.

**Takeaway:** an outbox is for writes that must never disagree. Be explicit about which ones are
allowed to.

## How does the outbox get to Kafka?

A publisher in the API polls every second (`OUTBOX_POLL_INTERVAL_MS=1000`), under a ShedLock
(`@SchedulerLock(name = "outbox-publisher")`) so one instance polls at a time. It claims a batch in a
short transaction and commits before it talks to Kafka:

```java
// Phase 1: fast claim — SELECT FOR UPDATE + mark SENDING, commit immediately
List<OutboxMessage> claimed = txTemplate.execute(status -> {
    List<OutboxMessage> batch = outboxMessageRepository
            .findPendingBatchForUpdate(OutboxStatus.PENDING.name(), batchSize, maxPerKey, maxPerProject);
```

(`railhook-api/.../service/OutboxPublisherService.java`)

The query ends in `FOR UPDATE SKIP LOCKED`, which Postgres documents as a way "to avoid lock
contention with multiple consumers accessing a queue-like table"
([PostgreSQL, *SELECT*](https://www.postgresql.org/docs/current/sql-select.html)). Rows go from
`PENDING` to `SENDING`, the transaction commits, and only then are they produced, so no lock is
held across a call to the broker. A row Kafka acknowledges becomes `PUBLISHED`, under a
`status = 'SENDING'` guard, so a late acknowledgement for a row that has since been reclaimed changes
nothing.

Each row is keyed by its endpoint's id, which puts all of one endpoint's work on one partition, and
a batch takes at most ten rows per endpoint and thirty per project, so one burst cannot starve
everyone else's announcements.

:::figure outbox-pipeline

What happens when things break:

- **Kafka is down.** The row goes to `FAILED` and a loop every 30 seconds retries it with
  exponential back-off. The Event is late, not lost.
- **A row fails five times.** It becomes `DEAD`, and even that does not lose the Delivery, because
  the Outbox row is only the announcement. The obligation is the Delivery row, still `PENDING`. A
  sweep in the worker finds Deliveries left `PENDING` with nothing scheduled for an hour and puts
  them on the retry path, which reaches Kafka through a different producer.

**Takeaway:** Kafka carries the news. Postgres keeps the obligation. Lose the news and the
obligation is still there to be announced again.

## Claims and fences: why don't two workers send the same Attempt?

In the worker, a Kafka message is not a command. It says a Delivery might be ready; the row decides.
Before anything is sent, the worker takes a **Claim**:

```sql
UPDATE deliveries SET status = 'PROCESSING', claim_token = :claimToken,
       last_attempt_at = now(), updated_at = now(), version = version + 1
WHERE id = :id AND status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now)
RETURNING *
```

(`railhook-worker/.../domain/repository/DeliveryRepository.java`)

One worker gets the row back. Every other copy of that message (a republished Outbox row, a
consumer rebalance) matches nothing. This line is where duplicates in Kafka stop.

Retries go through the same gate differently. The retry scheduler claims due rows itself and
publishes each with the token it claimed under; the consumer swaps that token for its own only
while the row still carries it, so a redelivered retry message claims nothing. And once a send to
Kafka succeeds, the scheduler stops writing to that row, because it is now the consumer's. Its
comment explains why: re-saving the old snapshot raced the consumer, "and when the consumer lost,
the retry partition stalled until a restart".

The `claim_token` is also a fence. A sweep hands anything `PROCESSING` for more than five minutes
back to `PENDING` and clears the token. A worker that stalled and wakes up writes against a token
the row no longer carries, and its write lands on nothing. The fence cannot recall a request a
stalled worker already put on the wire; it stops that request's outcome from counting.

Everything after the Claim is the **Attempt Runner**, one class for both directions. Its javadoc
lists six invariants, each of which "was once correct on one direction and wrong on the other".
Two of them are duplicate-delivery rules:

> No DB, Redis or Kafka work inside the reactive chain — a write there can trip the HTTP timeout and
> drive the failure path over a SUCCESS already written.

> No successor Attempt unless `AttemptStore#finalise` reports it wrote.

The first stops a slow database write from turning a delivered webhook into a "failed" one that
gets retried. The second is the fence applied to retries: an Attempt that lost its Claim may fail,
but it may not queue the next one.

**Takeaway:** a message is a hint and the row is the truth. Every write that matters is
conditional on still owning the row.

## So what does Railhook actually guarantee?

Step by step, where a duplicate can arise and what absorbs it:

| Step | How a duplicate arises | What absorbs it |
|---|---|---|
| Your POST to `/api/v1/events` | you retry after a timeout | `Idempotency-Key`, unique per project |
| A provider's webhook arriving | the provider resends | provider event id, unique per Source |
| Outbox to Kafka | publisher crashes after the send | the Claim: `WHERE status = 'PENDING'` |
| Kafka to worker | redelivery, rebalance | the Claim, or the retry token swap |
| A stalled worker | the sweep reassigns its row mid-Attempt | the fence keeps one outcome; your dedupe the second request |
| Endpoint back to Railhook | your `2xx` is lost on the way | **your** dedupe on `webhook-id` |

Railhook's side of each row is a unique index or a conditional `UPDATE`, not a timing assumption.
Two rows end at your endpoint, and the common one is the last, the case from the figure at the top: an Attempt that
succeeded at your end but was never recorded at ours. No transaction spans an HTTP call, so it
cannot be engineered away upstream. It is why this post says *exactly-once effect* and not
*exactly-once delivery*.

**Takeaway:** at-least-once end to end. Duplicates inside Railhook are absorbed there; the ones
that cross an HTTP call are yours to absorb, and one index does it.

## How long does Railhook keep trying?

A `408`, `429`, any `5xx`, a timeout or a connection error puts the Delivery back in Postgres with a
`next_retry_at`. Any other `4xx` or a `3xx` goes straight to Failed Messages: another Attempt will not
change that answer, but a person fixing a token or a URL will. The ladders are declared once:

```java
/** Outgoing: 1m, 5m, 15m, 1h, 6h, 24h. */
public static final String OUTGOING_DELAYS = "60,300,900,3600,21600,86400";

public static final int OUTGOING_MAX_ATTEMPTS = 7;

/** Incoming: 1m, 5m, 15m, 1h, 6h. */
public static final String INCOMING_DELAYS = "60,300,900,3600,21600";

public static final int INCOMING_MAX_ATTEMPTS = 5;
```

(`railhook-common/.../retry/RetryLadderDefaults.java`)

Outgoing: seven Attempts and six waits, about 31 hours and 21 minutes from first to last, each wait
jittered to between 50% and 150% so a thousand Deliveries that failed together do not return
together.

:::figure retry-ladder

Incoming: five Attempts, so the waits used by default are 1m, 5m, 15m and 1h, about 81 minutes in
all. The 6h rung is there for a Destination that raises its attempt count. The difference is
deliberate, and the class says so: holding your own Event for a day is a reasonable promise, while
for somebody else's webhook "a shorter give-up is the better one". The provider has retries of its
own.

A refusal before the request is built (the circuit breaker, a concurrency or rate limit) is a
**Deferral**: the Claim is released and no Attempt is spent, so an endpoint throttled for an hour
does not come out of it with its ladder used up. Behind both ladders sits a hard cap, 96 hours
outgoing and 24 incoming, after which anything outstanding goes to Failed Messages.

**Takeaway:** two ladders on purpose. Retrying someone else's webhook for a day helps nobody.

## Does a failed Delivery hold up the ones behind it?

With ordering on, for a while. Ordering is opt-in per Subscription and outgoing only. Each ordered
Delivery gets an endpoint-scoped **Sequence Number**, assigned *after* the ingest commit so a
rollback cannot burn one, and sent as `X-Sequence-Number`. A Delivery whose predecessors have not
resolved is parked in the **Ordering Buffer**, which is a Deferral: Claim released, token cleared,
no Attempt spent. When the predecessor succeeds or is abandoned, the cursor moves and whatever was
waiting is republished.

:::figure ordering-hold

The design choice is what happens when the predecessor does *not* recover. A strict wall would stop
the endpoint for as long as the ladder runs, up to 31 hours for one bad Event. Railhook yields: once
a parked Delivery has waited longer than `ORDERING_GAP_TIMEOUT_SECONDS` (60 by default, measured
from when it was first parked), it goes without its predecessor, and
`webhook_ordering_gap_timeout_total` counts it. If you need order through an outage, raise the
timeout and accept that one poisoned Event holds the endpoint, or carry a version in the payload and
check it on arrival.

**Takeaway:** by default, ordering survives a quick retry and gives way to an outage. That is a
choice, and it is yours to change.

## What does the receiver have to do?

Deduplicate, in the same transaction as the work. The
[Standard Webhooks specification](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)
defines `webhook-id` as an identifier that "remains the same no matter how many times a webhook that
has failed is retried". In Railhook it is the Delivery's id. `webhook-timestamp` is fresh on every
Attempt, and both are covered by the signature, over `id.timestamp.body`. So verify the signature,
then:

```javascript
// A receiver, sketched. `db` is your own Postgres client.
await db.tx(async (tx) => {
  const inserted = await tx.result(
    'INSERT INTO processed_webhooks (webhook_id) VALUES ($1) ON CONFLICT DO NOTHING',
    [req.header('webhook-id')],
  );
  if (inserted.rowCount === 0) return; // seen it: answer 2xx, do nothing
  await applyEvent(tx, req.body);
});
res.sendStatus(204);
```

The dedupe row and the side effect commit together, which is the outbox's lesson applied at the far
end: record the id in one place and act in another, and you have rebuilt the dual write this post
started with. Answer `2xx` to a duplicate too, or you invite the retry you are trying to absorb.

One deliberate exception: a **Replay** (the Time Machine in the dashboard) builds a *new* Delivery
from a stored Event, with a new `webhook-id`, so it gets past your dedupe. That is what a replay is
for. To recognise the same Event across replays, use `X-Event-Id`, which does not change.

**Takeaway:** one unique index on your side turns at-least-once into an exactly-once effect.

## What does a poller leave on the table, compared with CDC?

Railhook drains its outbox by polling. The alternative is change data capture: reading the outbox
from Postgres's write-ahead log through
[logical decoding](https://www.postgresql.org/docs/current/logicaldecoding-explanation.html), usually
with [Debezium's outbox event router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html).
Here is exactly what polling costs in our implementation, and what narrows each gap.

**A crash after the send, before `PUBLISHED`, publishes twice.** The row stays `SENDING`; after
`OUTBOX_SENDING_RECOVERY_SECONDS=300` it goes back to `PENDING` and out again. Narrowed three ways:
recovery waits well past the producer's 120-second delivery timeout; acknowledgements that arrive
after the batch stopped waiting are settled by `settleLateOutcomes()` instead of being dropped,
which, its javadoc notes, used to publish "a message Kafka had already accepted" a second time; and
the producer runs with `acks=all` and `enable.idempotence=true`, so its own internal retries "will
not result in duplicate entries in the log". What is left is absorbed by the Claim. CDC does not make this go
away on its own: Debezium states that it "provides at-least-once delivery guarantees", and its
exactly-once mode depends on Kafka Connect's exactly-once support
([Debezium, *Exactly once delivery*](https://debezium.io/documentation/reference/stable/configuration/eos.html)).

**Latency.** Up to one poll interval plus the batch before a worker hears anything. With ten rows
per endpoint per batch, one hot endpoint is announced at about ten Events a second at the default
poll. Both are configurable; the per-endpoint cap is the price of fairness. CDC would bring this
close to commit time.

**Database load.** A windowed query every second whether or not anything is pending. The
oldest-pending-age gauge is sampled inside that poll instead of querying on every Prometheus scrape;
the queue-depth gauges still count rows per scrape. Tailing the WAL does no work while nothing is
written.

**Ordering in Kafka.** One publisher polls at a time and a batch is sent in `created_at` order, but a
`FAILED` row is retried by a separate loop and can reach Kafka after newer rows for the same
endpoint. Nothing relies on Kafka's order for correctness: ordered Deliveries carry Sequence
Numbers and the ordering gate enforces them in the worker.

**Cleanup.** `PUBLISHED` rows are deleted after three days by an hourly job, in bounded batches;
`DEAD` rows are kept 90 days for a person. Alert on `outbox_oldest_pending_age_seconds` and
`outbox_queue_depth`, including `status="sending"`. An outbox that grows quietly is an outage with
the symptoms postponed.

| | Accepted Event can be lost | Can publish twice | What it adds |
|---|---|---|---|
| Commit, then publish | yes, on a crash between the two | no | nothing |
| Publish, then commit | no, but announces rolled-back rows | yes | nothing |
| Outbox + CDC (Debezium) | no | yes | Kafka Connect, replication slots |
| Outbox + poller (Railhook) | no | yes | a table, a poll, a cleanup job |

So why a poller? Railhook is meant to install with one command on one machine, and CDC means Kafka
Connect and replication slots to operate, and a stalled slot that holds WAL on disk until someone
notices. At volumes where the poll interval or the poll query starts to matter, CDC is the better
tool, and it is a reasonable future step here. It is not on a roadmap, and this post does not
promise it.

And you might not need Kafka at all. If Postgres is your only store, the Outbox is already a queue:
workers can claim rows with `SKIP LOCKED` and skip the broker. Railhook uses Kafka for partitioning
by endpoint, for retry tiers that are topics rather than sleeping consumers, and to keep the API's
write path apart from the workers. That buys throughput. The correctness comes from the transaction.

**Takeaway:** we narrowed every window a poller leaves, and what remains is one duplicate path the
Claim absorbs, plus about a second of latency.

## Read the code

[The docs](/docs/) cover [retries](/docs/outgoing/retries/), [ordering](/docs/outgoing/ordering/)
and [signatures](/docs/outgoing/signatures/) from the operator's side. The sequence diagrams are in
[`docs/ARCHITECTURE.md`](https://github.com/vadymkykalo/railhook/blob/main/docs/ARCHITECTURE.md),
and every class quoted here is in [the repository](https://github.com/vadymkykalo/railhook),
MIT-licensed, with its invariants in the javadoc rather than on a slide.

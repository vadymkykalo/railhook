# Railhook

Self-hosted webhook infrastructure. It carries events out of a customer's system to the
endpoints their users registered, and carries webhooks from third-party providers into the
destinations a customer nominated.

## Language

### The two directions

**Outgoing**:
The direction in which a customer's own event travels out of Railhook to an endpoint they
registered. Railhook is the sender and signs what it sends.
_Avoid_: outbound, egress, publish

**Incoming**:
The direction in which a third-party provider's webhook travels into Railhook and on to a
destination the customer nominated. Railhook is the receiver and verifies what it receives.
_Avoid_: inbound, ingress (that is the entry point, not the direction), relay

### Outgoing

**Event**:
Something a customer's system announced happened, with a type and a payload. It exists
independently of who, if anyone, is listening.
_Avoid_: message, notification, payload

**Endpoint**:
A URL registered by a customer that is willing to receive events, together with the secret
its signatures are computed from.
_Avoid_: webhook, subscriber, receiver, target

**Subscription**:
A standing statement that one endpoint wants events of a given type.
_Avoid_: binding, route, listener

**Delivery**:
The obligation to get one event to one endpoint, held until it succeeds or is abandoned.
Distinct from the individual tries it takes.
_Avoid_: send, dispatch, job

**Connection**:
One Endpoint together with the Subscriptions that point at it, named as one thing because
that is the unit a person configures, tests and turns off. It is a view, not a record:
nothing stores a Connection, and a change to one is a change to the Endpoint or to its
Subscriptions. The incoming direction's counterpart is a Source together with its
Destinations.
_Avoid_: integration, pipeline, link, channel

### Incoming

**Source**:
A third-party provider a customer has connected, together with what Railhook needs to
prove that a webhook genuinely came from it.
_Avoid_: provider (that is the vendor, not the connection), origin, sender

**Incoming Event**:
One webhook received from a source, kept as it arrived.
_Avoid_: request, capture, inbound event

**Destination**:
A URL a customer nominated to receive incoming events, together with how to authenticate
to it.
_Avoid_: forward target, sink, receiver

**Forward**:
The obligation to get one incoming event to one destination. The incoming counterpart of a
Delivery.
_Avoid_: relay, proxy, passthrough

### The attempt lifecycle

Shared by both directions.

**Attempt**:
One try at getting a Delivery or a Forward to its target: one HTTP request and whatever it
resolved to.
_Avoid_: try, retry (a retry is an attempt that is not the first), request

**Claim**:
Exclusive ownership of a Delivery or a Forward for the duration of one attempt. Held by
whoever is attempting, and revocable when they are presumed lost.
_Avoid_: lock, lease, reservation

**Attempt Runner**:
The module that owns what happens in an attempt and in what order — claiming, admitting,
sending, classifying the result, and finalising it.
_Avoid_: delivery service, executor, processor

**Attempt Store**:
The module that knows how one direction records its attempts, and how a Claim on it is
taken, proved and released. There is one per direction.
_Avoid_: repository, DAO, persistence layer

**Deferral**:
An outcome in which a Claim is released without an attempt being made, because the target
is not admissible yet. Not a failure — nothing was tried.
_Avoid_: skip, postpone, reject

**Retry Ladder**:
The schedule of how long to wait before each successive attempt, and how many attempts
there are before the obligation is abandoned.
_Avoid_: backoff policy, retry config

**DLQ**:
Where a Delivery or Forward lands once its Retry Ladder is exhausted: abandoned by
Railhook, kept for a human to decide about.
_Avoid_: dead letters, graveyard
_In the UI_: **Failed Messages**. The term the code and this document use is DLQ; the
term the product shows an operator is Failed Messages, because "DLQ" is vocabulary you
have to already know. The two are the same thing on purpose — do not rename one to
match the other.

**Replay**:
Building fresh Deliveries from Events already in the store, with the same content and
new Sequence Numbers, rather than re-sending the original Delivery.
_Avoid_: resend, retry (a retry is the next Attempt on the *same* Delivery; a replay is
a new Delivery)
_In the UI_: **Time Machine**.

### Ordering

**Sequence Number**:
An endpoint-scoped position stamped on a Delivery, establishing which of two Deliveries to
the same endpoint came first.
_Avoid_: offset, index, version

**Ordering Buffer**:
Where a Delivery waits when the Deliveries ahead of it in sequence have not finished.
_Avoid_: queue, holding area, parking lot

**Gap**:
A stretch of sequence numbers between what an endpoint has last received and what is
waiting, containing at least one Delivery that has not resolved.
_Avoid_: hole, missing range

### Crossing into the platform

**Outbox**:
The record that work has been accepted and still has to be announced, written in the same
breath as the work itself so the two cannot disagree.
_Avoid_: queue, buffer, pending table

**Tunnel**:
A developer's local port made reachable at a public URL for as long as their CLI stays
connected.
_Avoid_: proxy, ngrok, forwarder

### Tenancy

**Organization**:
The tenant. Everything a customer owns hangs off exactly one.
_Avoid_: account, tenant, workspace, team

**Project**:
A division of an Organization's work that endpoints, sources and API keys belong to.
_Avoid_: app, environment, namespace

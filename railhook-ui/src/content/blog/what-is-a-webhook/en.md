---
title: What is a webhook? How webhooks work, and the six ways they break in production
lead: A webhook is an HTTP POST one system sends another when something happens. Receiving one is easy. Receiving each one exactly once, verified, in an order you can use, is the actual job — here is how it works, and the six ways it breaks.
description: What a webhook is, how it compares with API polling, how to sign and verify one, and six ways webhooks fail in production — with the best practice for each.
date: 2026-09-21
author: Vadym Kykalo
tags: [webhooks, guide, security, reliability, http]
sourcesCheckedOn: 2026-09-19
---

A customer pays for order `ord_8412`. The shop sends your backend a webhook, and your handler
awards loyalty points, emails a receipt and calls the warehouse — which takes eleven seconds. The
sender's timeout is ten, so it marks the delivery failed and sends it again. The customer gets two
receipts and double points, and your logs show two perfectly healthy `200`s.

Nothing in that story is a bug in the usual sense. Every line of the handler did what it said. It
is what happens when code written for a function call meets a protocol built on retries — and it
is the first of six failures this guide walks through, after the basics every one of them depends
on.

## What is a webhook?

A webhook is an HTTP `POST` that one system sends to a URL another system registered, at the
moment an event happens. The receiver registers once — "when an order is created, tell me at
this URL" — and from then on the sender calls it every time. This is what one looks like, signed
the way the open [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)
specification describes:

```http
POST /webhooks/orders HTTP/1.1
Host: api.example-shop.com
Content-Type: application/json
Content-Length: 186
webhook-id: msg_2mQ8hZk3Xv9aR1cT
webhook-timestamp: 1789983612
webhook-signature: v1,pX/4CnjtyRIdKhtP4FEBLl0a8pgk0IErOOHRkgsduog=

{
  "type": "order.created",
  "timestamp": "2026-09-21T09:40:12Z",
  "data": {
    "order_id": "ord_8412",
    "customer_id": "cus_2291",
    "total": 42.50,
    "currency": "EUR"
  }
}
```

:::figure webhook-anatomy

The signature is real. It was made with the secret
`whsec_DioEXswemwdu+ScHzpEImNzhtrASzXe0vxCXPBmwuTM=`: paste the body, that secret and the three
headers into the [signature verifier](/tools/webhook-signature) and it will say the signature is
valid — and that the timestamp is far too old to accept. Both answers are correct, and the second
one is a security feature.

## Webhook vs API polling

The alternative to being told is asking: calling the other system's API on a timer to see whether
anything changed.

:::figure polling-vs-push

| | Polling an API | Receiving a webhook |
|---|---|---|
| Who starts the request | You, on a timer | The sender, when something happens |
| Delay | Up to one polling interval | Usually seconds |
| Requests when nothing happens | One per interval, forever | None |
| What you must run | A scheduled job | A public HTTPS endpoint |
| If you are down | You catch up on the next poll | You depend on the sender's retries |

Shorten a polling interval and you pay in requests and rate limits; lengthen it and you pay in
lag. A webhook costs one request per event. But read the last row twice: polling fails safe,
because a missed poll is repeated by the next one, while a webhook fails however the *sender*
decides. That is why polling keeps a job in a good webhook integration — as the slow
reconciliation that catches whatever the webhooks missed.

## Sending webhooks: what the sender decides

**Event types.** Conventionally `resource.verb` in the past tense: `order.created`,
`invoice.paid`. The Standard Webhooks spec asks for a full-stop delimited `type`, with a
`timestamp` and a `data` object next to it. Treat types as a public API: renaming one breaks every
receiver that routes on it.

**Payloads.** A full snapshot of the resource saves the receiver a round trip; a thin
notification carrying only an id makes it fetch the current state, which is never stale.
[Stripe](https://docs.stripe.com/webhooks) offers both and calls them exactly that.

**Subscriptions.** Which endpoint wants which event types. Sending everything to everyone just
makes every receiver filter traffic it never asked for.

**Signing.** Anyone can `POST` to a public URL, so the receiver needs proof the request came from
you, unaltered. That proof is an HMAC — a hash of the message keyed with a secret only the two of
you hold. Standard Webhooks signs `webhook-id`, `webhook-timestamp` and the raw body joined by full
stops, with HMAC-SHA256, and sends the base64 result after a `v1,` prefix. The secret is 24 to 64
random bytes, base64-encoded, with a `whsec_` prefix.

**A replay window.** The timestamp is signed so that a captured request cannot be sent again
tomorrow: the receiver rejects anything older than a few minutes. The spec leaves the tolerance to
you; Stripe's libraries default to five minutes, a sensible place to start.

**Timeouts and status codes.** The spec recommends a request timeout "somewhere between 15 and
30s". `2xx` is success and anything else is retried — including redirects, which
[Stripe](https://docs.stripe.com/webhooks) also counts as failures. Two codes carry extra meaning:
`410 Gone` asks the sender to disable the endpoint, `429 Too Many Requests` asks it to slow down
([MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status) has the full list).

## Receiving webhooks: four jobs, in order

### Verify the signature on the raw bytes

Verification means recomputing the HMAC and comparing. What breaks it most often is *what* you
compute it over: it must be the exact bytes that arrived.

Look at the example body. It says `"total": 42.50`. Parse it and serialise it back and you get
`{"type":"order.created",…,"total":42.5,…}` — no whitespace, no trailing zero. Same data, different
bytes, a completely different HMAC. Many frameworks parse JSON before your handler runs, so the
body you are handed is already the wrong one. Stripe puts it bluntly: "Any manipulation to the raw
body of the request causes the verification to fail."

In Node with Express, ask for the raw bytes on that one route:

```javascript
import crypto from 'node:crypto';
import express from 'express';

const secret = Buffer.from(process.env.WEBHOOK_SECRET.replace(/^whsec_/, ''), 'base64');
const TOLERANCE_SECONDS = 5 * 60;

// rawBody is a Buffer: the bytes that arrived, before any JSON parser saw them.
function verifyWebhook(rawBody, headers) {
  const id = headers['webhook-id'];
  const timestamp = headers['webhook-timestamp'];
  const signatures = headers['webhook-signature'];
  if (!id || !/^\d+$/.test(timestamp ?? '') || !signatures) return false;

  // The replay window: a captured request is useless five minutes later.
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > TOLERANCE_SECONDS) return false;

  const expected = crypto
    .createHmac('sha256', secret)
    .update(`${id}.${timestamp}.`)
    .update(rawBody)
    .digest();

  // Space-separated: during a secret rotation there is one signature per secret.
  return signatures.split(' ').some((entry) => {
    const [version, value] = entry.split(',');
    if (version !== 'v1' || !value) return false;
    const received = Buffer.from(value, 'base64');
    return received.length === expected.length && crypto.timingSafeEqual(received, expected);
  });
}

const app = express();
app.post('/webhooks/orders', express.raw({ type: 'application/json' }), (req, res) => {
  if (!verifyWebhook(req.body, req.headers)) return res.sendStatus(400);
  // …record the id, enqueue, answer (below)
  res.sendStatus(200);
});
```

The same in Python, where Flask's `request.get_data()` returns the untouched bytes:

```python
import base64
import hashlib
import hmac
import os
import time

SECRET = base64.b64decode(os.environ["WEBHOOK_SECRET"].removeprefix("whsec_"))
TOLERANCE_SECONDS = 5 * 60


def verify_webhook(raw_body: bytes, headers) -> bool:
    msg_id = headers.get("webhook-id")
    timestamp = headers.get("webhook-timestamp", "")
    signatures = headers.get("webhook-signature")
    if not (msg_id and signatures and timestamp.isascii() and timestamp.isdigit()):
        return False

    # The replay window: a captured request is useless five minutes later.
    if abs(time.time() - int(timestamp)) > TOLERANCE_SECONDS:
        return False

    signed = f"{msg_id}.{timestamp}.".encode() + raw_body
    expected = base64.b64encode(hmac.new(SECRET, signed, hashlib.sha256).digest())

    # Space-separated: during a secret rotation there is one signature per secret.
    for entry in signatures.split():
        version, _, value = entry.partition(",")
        if version == "v1" and hmac.compare_digest(value.encode(), expected):
            return True
    return False
```

Three details are deliberate. The comparison is constant-time (`timingSafeEqual`,
`compare_digest`), as the spec requires: a plain `==` returns sooner the earlier two strings
differ, and leaks the answer a byte at a time. The secret is base64-decoded after `whsec_` is
stripped — using the string as-is is a classic reason a correct implementation never verifies
anything. And the header may carry several signatures, which matters the day you rotate. If you
would rather not own this code, the Standard Webhooks project publishes libraries that do exactly
this for most languages.

### Answer in milliseconds, work later

The sender is holding a connection open and counting.
[GitHub](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
gives you ten seconds, then "considers the delivery a failure". Stripe publishes no number; it
says to return `2xx` "before any complex logic that could cause a timeout", and to process events
from an asynchronous queue.

:::figure receiver-ack

So the handler does the minimum: verify, record the event id, enqueue, return `200`. The email, the
warehouse call, the loyalty points — the eleven seconds from the opening — happen in a worker,
where nobody is waiting and a failure is yours to retry.

### Deduplicate on the event id

A sender that retries will eventually deliver something twice. The spec says `webhook-id`
"remains the same no matter how many times a webhook that has failed is retried", and tells
receivers to use it as an idempotency key. Other providers have their own: `X-GitHub-Delivery`,
`X-Shopify-Webhook-Id`, the event's `id` at Stripe. The robust version is a unique constraint and
an insert that is allowed to do nothing:

```python
def accept(conn, msg_id: str, raw_body: bytes) -> bool:
    """Stores the event once. Returns False if this id was already accepted."""
    cursor = conn.execute(
        "INSERT INTO received_webhooks (msg_id, body) VALUES (%s, %s) "
        "ON CONFLICT (msg_id) DO NOTHING",
        (msg_id, raw_body),
    )
    return cursor.rowcount == 1
```

A duplicate still gets a `200`. You have it already, and an error would only make the sender try
again.

### Never assume order

Unless a sender explicitly promises ordering, assume there is none. Stripe says outright that it
"doesn't guarantee the delivery of events in the order that they're generated". Failure four below
is what to do about it.

## Six ways webhooks break in production

### 1. Your endpoint is down: who retries, and for how long?

A deploy, a crashed pod, an expired certificate. The sender gets a connection error or a `5xx`,
and what happens next is entirely its policy. Good senders retry with exponential back-off — each
wait longer than the last, with random jitter so a backlog of retries does not land in the same
second. The Standard Webhooks spec's example schedule starts immediately and runs for more than
three days.

:::figure retry-backoff

Not every sender retries. GitHub "does not automatically redeliver failed webhook deliveries"
([GitHub](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries));
you redeliver by hand or through its API. Our
[comparison of Stripe, GitHub and Shopify](/blog/stripe-github-shopify-when-your-endpoint-is-down)
puts three real policies side by side.

**Takeaway:** know each sender's retry schedule before the outage, and never return `2xx` for an
event you have not stored — a `200` is a promise that you have it.

### 2. Your endpoint is slow: a timeout is a retry you asked for

A timeout looks like downtime to the sender, with one nasty difference: your handler may have
finished the work. That is the opening story. A slow endpoint converts directly into duplicate
processing, and it gets slower under load — exactly when a burst of retries arrives.

**Takeaway:** acknowledge inside the budget, whatever the work costs. If you do not know your
handler's 99th-percentile latency, measure it before anything else.

### 3. The same event arrives twice

Duplicates come from retries after a lost response, from redeliveries someone triggered by hand,
and from the sender itself. Stripe's docs say so in one sentence: "Webhook endpoints might
occasionally receive the same event more than once."

:::figure duplicate-delivery

**Takeaway:** check the id before any side effect, in the same transaction that records the event.
A check in memory, or one after the email has gone, is not a check.

### 4. Events arrive out of order

An `order.created` whose first attempt failed can arrive after the `order.updated` and
`order.cancelled` that followed it. Apply them in arrival order and a cancelled order comes back
to life.

:::figure out-of-order

Compare a version or an `updated_at` on the resource and ignore anything older than what you
hold — or treat the webhook as a nudge and fetch the current state from the sender's API. Be wary
of the event timestamp as a tiebreaker: Stripe notes its events record `created` in seconds, so
distinct events can share one.

**Takeaway:** every handler must produce the right state whatever order its events arrive in.

### 5. Signatures suddenly stop verifying

This arrives as a wall of `400`s, and the cause is nearly always one of five:

- The body was parsed and re-serialised before verification.
- The wrong secret: test instead of live, or another endpoint's.
- A `whsec_` secret used as a literal string instead of decoded.
- A drifting server clock, so every timestamp looks stale.
- A secret rotated with no overlap.

The last one is an outage you schedule yourself. If the old secret dies the instant a new one is
issued, every request between that moment and your deploy fails. Senders avoid it by signing with
both secrets for a while: the spec describes exactly this, and Stripe keeps the previous secret
valid for up to 24 hours, with one signature per active secret.

:::figure secret-rotation

**Takeaway:** accept any valid `v1` in the header, deploy the new secret inside the overlap, and log
*why* verification failed — stale timestamp, no matching signature, missing header — never a bare
`400`.

### 6. Events stop, and nobody notices

The quietest failure is the worst. The sender exhausts its retries and gives up. A subscription is
disabled after repeated failures — Shopify removes it
([Shopify](https://shopify.dev/docs/apps/build/webhooks/troubleshooting-webhooks)). Or your own
handler catches an exception, logs it at `debug` and returns `200`. From then on nothing errors;
events just stop, and the first symptom is a customer asking where their order went.

The defence is a delivery log on whichever side you control:

| Record | Why you will want it |
|---|---|
| Event id and type | To find one event, and to prove it was a duplicate |
| Every attempt: time, status, latency | To tell "down" from "slow" from "rejected" |
| The response body, truncated | Most failures explain themselves there |
| Final outcome | To count what was abandoned, not just what failed once |

**Takeaway:** alert on the failure rate *and* on silence. An endpoint that received a thousand
events an hour yesterday and none today is not healthy just because nothing is failing.

## Testing webhooks locally

**See what arrives.** A request bin is a throwaway URL that records every request sent to it. Our
free [webhook tester](/tester) is one, keeping the latest hundred requests for 24 hours: point a
provider at it and read the real headers and body before you write a handler.

**Reach your laptop.** A tunnel gives `localhost` a public HTTPS URL. Stripe's docs suggest ngrok,
and its CLI forwards events with `stripe listen --forward-to localhost:4242/webhook`.

**Replay real payloads.** A captured body and its headers are a test fixture that is exactly what
production sends:

```bash
curl -X POST http://localhost:3000/webhooks/orders \
  -H 'Content-Type: application/json' \
  -H 'webhook-id: msg_2mQ8hZk3Xv9aR1cT' \
  -H "webhook-timestamp: $(date +%s)" \
  -H "webhook-signature: v1,$SIGNATURE" \
  --data-binary @order-created.json
```

`--data-binary` sends the file byte for byte; plain `-d` strips newlines and changes the
signature. A replay needs a fresh timestamp and so a fresh signature — the replay window doing its
job. When a signature will not verify, the [signature verifier](/tools/webhook-signature) checks
body, secret and header in your browser without sending them anywhere.

**Break it on purpose.** Return `500` and watch the retries arrive. Sleep past the timeout. Send
the same payload twice. Send `order.updated` before `order.created`.

## Build it yourself, or put a gateway in front?

For one sender and one receiver, build it: verification, a table of seen ids and a queue are a
day's work, and the code above is most of it.

It gets harder as the edges multiply. Sending webhooks to your own customers means storing every
event before the first attempt, a retry scheduler that survives restarts, isolation so one slow
customer does not delay the rest, a delivery log they can read, replay and secret rotation.
Receiving from several providers means several signing schemes and retry policies. None of it is
exotic, but it is a system. A gateway is that system already built — at the price of one more
component that must stay up and that holds your signing secrets.

### How Railhook handles it

Railhook is an open-source webhook gateway, MIT-licensed; these are its defaults. Outgoing
webhooks are stored before the first attempt and attempted up to seven times, waiting one minute,
five minutes, fifteen minutes, one hour, six hours and twenty-four hours — about thirty-one hours
in all, each wait jittered between half and one and a half times its tier. Webhooks it relays
onward for you get a shorter ladder of five attempts. By default every delivery carries the
Standard Webhooks headers shown above, and after a secret rotation both secrets sign for a 24-hour
grace window. Every attempt is logged with its status code and response, and any stored event can
be replayed. [The docs](/docs/) have the details.

*Provider behaviour quoted above was read from each provider's own documentation on the date at the
top of this article. These policies change; follow the links before you build on a number.*

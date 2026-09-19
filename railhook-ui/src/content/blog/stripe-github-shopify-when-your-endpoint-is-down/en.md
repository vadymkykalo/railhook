---
title: What Stripe, GitHub and Shopify actually do when your endpoint is down
lead: Three providers, three completely different answers. One keeps trying for three days, one gives up before your pager has finished buzzing, and one deletes your subscription.
description: Stripe, GitHub and Shopify retry failed webhooks on wildly different schedules — and give up in wildly different ways. What each one does, with links to their own docs, and what changes when a gateway sits in front.
date: 2026-09-19
author: Vadym Kykalo
tags: [webhooks, reliability, stripe, github, shopify]
sourcesCheckedOn: 2026-09-19
---

Your deploy goes badly. For eleven minutes the container that handles `/webhooks` returns 502, and
then it comes back. Nobody outside the team noticed.

What happened to the events that arrived during those eleven minutes depends entirely on who sent
them. If they came from Stripe, they are still coming — Stripe will be knocking for days. If they
came from GitHub, they are gone, and nothing will ever bring them back except you, by hand. If they
came from Shopify, you may also have lost the subscription itself.

That is not three implementations of one idea. It is three different products that share a name.

:::figure provider-retries

## Stripe: three days of exponential back off

Stripe is the most patient of the three, by a wide margin. Its documentation is explicit:

> Stripe attempts to deliver events to your destination for up to three days with an exponential
> back off in live mode.

In a sandbox the schedule is much shorter — "three times over the course of a few hours" — which is
worth knowing before you conclude from a test-mode experiment that your retry handling works.
([Stripe, *Webhooks*](https://docs.stripe.com/webhooks))

Two things Stripe does *not* publish are worth naming, because their absence is often filled in with
a guess. It does not publish the individual back-off intervals, and it does not publish a request
timeout in seconds. What it says instead is to return a `2xx` before doing the work:

> Your endpoint must quickly return a successful status code (2xx) before any complex logic that
> could cause a timeout.

So the honest reading is: assume the budget is small, acknowledge first, process afterwards.

Stripe also stops early if the destination stops existing. If you disable or delete an endpoint
before the next retry lands, the remaining retries are cancelled — but if you re-enable it in time,
they resume. And if you need an event back after the fact, the Dashboard's **Resend** works for up
to 15 days after the event was created, and `stripe events resend` from the CLI for up to 30.

Two sentences from the same page are the ones most people skip, and both are about correctness
rather than availability:

> Stripe doesn't guarantee the delivery of events in the order that they're generated.

> Webhook endpoints might occasionally receive the same event more than once.

Three days of retries is a generous promise, and it is also the reason a Stripe integration that
ignores idempotency will eventually double-charge something.

## GitHub: one attempt, and that is all

GitHub sits at the other end. Its best-practices page gives you a hard budget:

> GitHub requires your server to respond with a 2XX status code within 10 seconds. If your server
> takes longer than that to respond, then GitHub terminates the connection and considers the
> delivery a failure.

And then, on the page about failed deliveries, the sentence that decides your architecture:

> GitHub does not automatically redeliver failed webhook deliveries.

There is no ladder. There is no back off. There is one request, a ten-second window, and a result.
([GitHub, *Best practices for using webhooks*](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks) ·
[*Handling failed webhook deliveries*](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries))

What GitHub gives you instead is a record and a button. Deliveries from the past three days can be
inspected and redelivered — from the webhook's settings page, or through the API, which is what you
would script if you wanted something resembling automatic recovery.
([*Redelivering webhooks*](https://docs.github.com/en/webhooks/testing-and-troubleshooting-webhooks/redelivering-webhooks))

One detail makes that scriptable safely: a redelivery carries the same `X-GitHub-Delivery` identifier
as the original. If you key your deduplication off that header, replaying a day's worth of failures
is not a day's worth of duplicated work.

It is worth being precise about what GitHub does *not* say, too. It does not publish an ordering
guarantee, and it does not publish a formal at-least-once promise. Treat the delivery id as the
contract, because it is the only one written down.

## Shopify: eight tries, four hours, then the subscription is gone

Shopify's budget is the tightest of the three, and its failure mode is the only one that is
destructive.

The timeouts come in two parts: "a one-second connection timeout and a five-second timeout for the
entire request". Then:

> Shopify retries failed webhook calls up to eight times in a four-hour period.

And the part that catches people:

> After multiple failures in a 24-hour period, the webhook subscription is removed.

Removed subscriptions receive nothing until you create them again.
([Shopify, *Troubleshooting webhooks*](https://shopify.dev/docs/apps/build/webhooks/troubleshooting-webhooks) ·
[*Subscribe to webhooks with HTTPS*](https://shopify.dev/docs/apps/build/webhooks/subscribe/https))

Read that sequence again as an incident timeline. Four hours of retries, a day of continued failure,
and then the pipe is not failing any more — it is simply not there. Nothing in your logs says
"events stopped", because from your side nothing arrives and nothing errors. The next symptom is a
support ticket about an order that never synced, some days later.

Shopify's deduplication advice is the same as everyone's, keyed on `X-Shopify-Webhook-Id`: look it
up, and if you have seen it, return success without doing the work again.

## Side by side

| Provider | Timeout | Retries | When it gives up | Getting it back |
|---|---|---|---|---|
| ![](/logos/brand/stripe.svg) Stripe | not published; return 2xx first | exponential back off, up to 3 days (live) | after 3 days | Dashboard resend, 15 days; CLI resend, 30 days |
| ![](/logos/brand/github.svg) GitHub | 10 seconds | none — one attempt | immediately | redeliver by hand or via the API, past 3 days |
| ![](/logos/brand/shopify.svg) Shopify | 1s connect, 5s total | up to 8 calls in 4 hours | after repeated failure within 24 hours, **the subscription is removed** | recreate the subscription |

For a fourth data point: Slack's Events API wants a `2xx` within three seconds and retries exactly
three times — "nearly immediately", then after 1 minute, then after 5 — and an app failing more than
95% of deliveries in an hour has its subscriptions temporarily disabled, with an email to the app's
owner. ([Slack, *Events API*](https://docs.slack.dev/apis/events-api/))

## What you actually lose

Add it up and the loss is not really "some events". It is three separate things.

**The event.** Only for GitHub is this immediate, but it is total: ten seconds of unavailability and
that delivery exists nowhere except in GitHub's own delivery log, for three days, behind a button.

**The subscription.** Only Shopify does this, and it is the one failure that outlives the incident.
Everything else heals when your service comes back.

**The certainty.** This is the quiet one. Stripe states plainly that order is not guaranteed and
duplicates happen; GitHub and Shopify hand you an id and expect you to do the work. None of these
providers will tell you, afterwards, which events you processed. Your own records are the only
answer, and during an outage those are exactly the records you did not write.

## What a gateway in front of them changes

Putting something in front of your application does not make any of this go away — the provider's
schedule is still the provider's schedule. What it changes is *who has to survive it*.

:::figure gateway

The receiving side is a much smaller thing to keep up than your application. It verifies the
signature, writes the event down, and answers `2xx`. It does not call your database, run your
business rules or talk to a payment processor, so the five seconds Shopify allows and the ten
GitHub allows stop being budgets you are anxious about. The provider's retry policy stops mattering
at that first box, because the first box is almost never the thing that was down.

From there, three things become yours instead of theirs:

1. **Deduplication.** One place that has already seen `X-Shopify-Webhook-Id` or `X-GitHub-Delivery`,
   so at-least-once delivery stops being a property every service has to implement separately.
2. **Retries on a schedule you chose.** Not three days if you do not want three days, and not one
   attempt if one attempt is absurd.
3. **Replay.** The event was stored before anything was attempted, so "resend the last hour" is a
   query, not a plea to a provider's retention window.

There is an honest cost, and it should be said plainly: you have added a component. It is now the
thing that must be up, and it is now the thing that has your signing secrets. That trade is worth it
when several services consume the same webhooks, or when you integrate with more than one provider
and are tired of reimplementing three different retry semantics. It is probably not worth it for a
single service consuming a single Stripe endpoint.

## How Railhook does it

Railhook is the gateway shape above, and its numbers are not a secret.

Incoming webhooks are verified against the provider's scheme, deduplicated, and stored before
anything is attempted. Outgoing deliveries run a ladder of seven attempts with waits of one minute,
five minutes, fifteen minutes, one hour, six hours and twenty-four hours — about thirty-one hours
from the first attempt to the last.

:::figure retry-ladder

Relaying somebody else's webhook onward uses a deliberately shorter ladder, because holding another
system's event for a day is a different promise from holding your own. Every attempt is recorded
with its status code and its response, and any stored event can be replayed — seven days of history
on the free cloud plan, and whatever you configure when you run it yourself.

It is MIT-licensed, and the retry ladders are in the source rather than in a marketing claim.
[The docs](/docs/) have the rest.

*Every provider figure above was read from that provider's own documentation on the date in the
note at the top of this article. These policies change; follow the links before you build on a
number.*

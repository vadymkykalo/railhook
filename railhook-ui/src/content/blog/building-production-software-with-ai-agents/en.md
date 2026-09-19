---
title: How to build software with an AI coding agent that you can actually trust
lead: An AI agent wrote nearly all of the code behind a production system I run. It is fast, fluent and sometimes confidently wrong. What made its work trustworthy wasn't a better prompt. It was a codebase that refuses wrong changes, and a person who decides what "wrong" means.
description: What building a production system with an AI coding agent taught me: where agents fail, why instructions aren't enough, and how to make AI-written code trustworthy.
date: 2026-09-22
author: Vadym Kykalo
tags: [ai agents, claude code, engineering, testing, software quality]
sourcesCheckedOn: 2026-09-19
---

For several releases, every large event my webhook gateway delivered went out as a compressed blob
instead of JSON. The database knew the payload was compressed. One of the two services that read it
had never been told to look. Every test passed, and I didn't notice until a review I asked for went
looking.

I didn't write that bug, and I didn't write the fix. An AI coding agent, Claude Code, wrote both,
the same way it wrote nearly all of [Railhook](https://github.com/vadymkykalo/railhook): over many
rounds of me setting direction, reviewing and pushing back. I'm a senior Java developer. My part was
knowing what correct looks like for this kind of system.

The fix did more than add the missing field. It added a check that fails the build whenever the two
services disagree about what the database contains. That bug can't come back, and no prompt I could
have written would make the same promise.

That is the main thing I learned. **I can't review every line an agent writes. What I can do is
build a codebase that refuses wrong changes on its own, and keep for myself the decisions about what
"wrong" means.** Below are the lessons that got me there, each with the moment that taught it.

## 1. I learned the agent is fluent, not careful

Railhook moves webhooks in two directions: out from a customer's system to their endpoints, and in
from providers like Stripe to the customer's services. The incoming side started life as a copy of
the outgoing side. Both copies read well and both had tests. Over time, fixes landed on one copy and
never reached the other.

When I had a review compare them side by side, the worst gap was this: on the incoming side, a slow
database write *after* the receiver had already answered "OK" could be mistaken for a timeout. The
error path then marked the delivery as failed and sent it again. A webhook the receiver had already
accepted went out twice.

Nothing in that code looked wrong to me. That's what makes it dangerous. An agent writes plausible
code very quickly, and it will happily write the same logic twice. Each copy passes its own review.
The drift between them is invisible until something breaks in production.

The fix I asked for was structural. Both directions now run through one shared implementation, and
the rules that were each learned from a real bug sit right at the top of that file, in plain
sentences: once the receiver has answered "OK", nothing that goes wrong afterwards may turn it into
a retry. The agent reads the file it is about to change, so that is where the scars have to be.

The same file-level note works in the other direction. Agents love symmetry. My two directions
deliberately give up after different amounts of time, and without a sentence saying "these differ on
purpose, don't make them agree," an eager agent would "fix" that.

**What I took from it: collapse duplication early, and write what went wrong into the code the agent
will read, not into a wiki it will never open.**

## 2. It told me things that weren't true, in the same confident voice

My upgrade script had a comment saying the background worker restarts last, after the database
migrations have run. The code directly below it restarted the worker first. On one production
deploy, the new worker came up before the new schema existed, failed its startup check, crashed and
restarted. The only trace was a restart counter.

It wasn't a one-off. My project's roadmap once said the alerting system "already counts" consecutive
failures. In fact nothing ever triggered an alert, so every alert rule a user had created did
nothing at all. A backup script promised a verification step that existed nowhere.

Every one of those sentences was specific, fluent and plausible. None of them was true. An agent
writes a comment or a README the way it writes code: it produces what *should* be there. It can't
tell that apart from what *is* there, and it sounds exactly as sure either way.

What changed: the upgrade order became a test. Anything a machine can derive is now derived instead
of written. The API spec is generated from the running server and compared in CI, the frontend's
types are generated from the spec, and the configuration reference in the docs is generated from the
file that defines the configuration. Nobody, human or agent, hand-writes an endpoint table any more.

**What I took from it: everything an agent writes in prose is a claim, not a fact. I make each claim
checkable now, or I don't let it stay.**

## 3. I learned that a written rule is only a request

Claude Code reads a short instructions file from the repository at the start of every session, plus
another from any subdirectory it works in ([Claude Code
docs](https://code.claude.com/docs/en/memory)). I keep one for the whole repository and one each for
the frontend and the docs. They help a lot. Each rule carries its reason, because the reason is what
lets the agent handle a case the rule didn't foresee. Next to them sits a glossary that names each
domain concept once and lists the synonyms to avoid, because two names for one thing is how two
implementations of one thing start.

Then there was the merge rule. It said "squash merge is preferred," with no reason given. A release
was squash-merged into the main branch, which rewrote its history, and the next release conflicted
on files that were byte-for-byte identical. I had the rule rewritten, with the reason spelled out.
The next day, another branch was squash-merged into main anyway.

Nobody set out to break the rule. That is simply what a written rule is: Claude Code's own
documentation says these files are "context, not enforced configuration." The rule now lives in the
repository's branch settings, which refuse a squash merge into main.

**What I took from it: write rules down, with reasons. When a rule is broken once, rewrite it. When
it's broken twice, stop asking and enforce it.**

## 4. The most valuable thing I built is a codebase that says no

A raw SQL insert in a nightly job bypassed the filter that scopes every query to one customer. At
five past midnight, every night, it failed on a missing customer id, and a `catch` block swallowed
the error. Nothing reported it, and no test was looking.

The answer is a kind of test I've come to rely on more than any other: a *ratchet*. It scans the
codebase for a pattern, accepts today's known exceptions, each with a written reason, and fails the
build on any new one. Mine now require every raw SQL query to state whether it is scoped to a
customer, forbid service methods from taking a customer id as a parameter, and make every endpoint
that changes data declare who may call it.

Two details matter far more with an agent than with a human colleague. First, a ratchet must check
that it actually found something to scan. A scan that silently matches nothing passes, and you'd
never know. Second, its failure message has to say how to fix the problem, because the next reader
of that message is the agent:

```java
assertEquals(Set.of(), offenders,
        "These service methods take an organization as a parameter. [...] "
                + "If the organization genuinely comes off a row rather than off the caller, "
                + "add the method to DOCUMENTED_EXEMPTIONS with a reason.");
```

The agent reads that message and does what it says. It is the most effective prompt in the whole
repository, because it arrives at the exact moment of the mistake.

Recurring procedures went the same way. How to change the database schema, and how to name a test so
it runs in the right CI job, became *skills*: instructions the agent loads when a task matches them
([Claude Code docs](https://code.claude.com/docs/en/skills)). "Write the failing test first" is a
single line in the instructions file. Put together, every change goes around the same loop:

:::figure ai-harness-loop

**What I took from it: I can't review everything the agent writes, so the codebase reviews it. Every
class of bug I find becomes a check that refuses the whole class.**

## 5. Some bugs only exist on a real phone, or in production

My registration form was cut off at the right edge of an iPhone. iOS Safari zooms into any input
whose text is smaller than 16 pixels, and after that the page no longer fits the screen. No unit
test can see that, and neither could the agent. I had to hold the phone.

Now a browser test opens every page at a phone width and a desktop width and fails on sideways
scrolling or on any input an iPhone would zoom into. Later it caught the same problem on a new page,
but only once that page had been added to the test's list.

Deploys taught the same lesson. One release answered "200 OK" while the server was still serving the
previous version of the site, and the deploy pipeline called it a success. Now the pipeline asks the
live site which version it is serving, and fails if the answer is wrong.

**What I took from it: check the software where users meet it. A green pipeline is not the
release.**

## 6. A guardrail only refuses the bug it names

After all of this, the most serious bug still got through. An API key for one project could reset
the signing secret of an endpoint in *another* project of the same account, and get the new secret
back.

My tenant checks did exactly what they were built to do. They guard the boundary between customers.
Nobody had named the boundary between projects *inside* one customer, so nothing guarded it. The fix
came with tests for every place that looks something up by project today. A check that refuses the
*next* unscoped lookup doesn't exist yet, and I'd rather say so than pretend otherwise.

:::figure ai-bug-guardrail

**What I took from it: every guardrail has the shape of the bug that created it. The next incident
will come from a kind of bug nobody has named yet, so after each fix I ask what the kind is, not
only what the bug was.**

## What stayed with me

In my experience the agent is genuinely good at a lot. It reads a large codebase quickly, follows
conventions once they're written down, writes thorough tests when asked, and explains in a commit
message why a change is shaped the way it is. What it doesn't do is decide.

Architecture stays with me, and so does the honest record of what each choice cost. I keep separate
copies of shared data models in two services. That decision stands, it caused the bug this article
opened with, and a check now pays for it. The roadmap says plainly what the system won't do,
including exactly-once delivery. The architecture is good and not perfect, and the docs say where:
the outbox is polled rather than streamed, there's no distributed tracing yet, and some recovery
procedures aren't written down.

Scope stays with me too. Each branch has one written task, and the repository's instructions call it
"the only one that authorizes writing code." Ideas live somewhere else and are "not work orders."
When several agents work at once, each gets its own branch and working copy, so they can't step on
each other.

And production stays with a person. Deploying is a manual step that someone approves by name,
because, in the deploy workflow's own words, it is "the one action here that is not reversible by
reverting a commit."

## What I'd tell you before you try this

**You won't type less. You'll decide more.** The work moves from writing code to saying what correct
means, precisely enough that a machine can check it.

**The agent finishes fast, and the edges take the time.** Code that looks done arrives almost
immediately. Most of the real work is finding where it's wrong at the edges: two copies that drifted
apart, a comment describing code that isn't there, a boundary nobody named.

**Believe checks, not sentences.** An agent sounds just as confident when it's wrong as when it's
right. Your trust has to come from something that runs.

**Every surprise is a check you haven't written yet.** None of the guardrails I've described was
designed up front. Each grew out of something that went wrong, and I don't know another way to grow
them.

**Keep production human.** Let the agent write the deploy script. Don't let it press the button.

### The checklist

- One short instructions file per area of the code, with the reason next to every rule.
- A glossary that names each concept once and lists the words to avoid.
- Failing test first, and a written procedure for where each kind of test lives.
- A ratchet for every class of bug you find: known exceptions with reasons, a check that the scan
  found something, and a failure message written as an instruction.
- Generated references (API spec, types, configuration) with a CI check for drift.
- One implementation of anything that must behave the same in two places.
- The rules learned from past bugs, written into the files where the next change will happen.
- A real browser at phone width in CI.
- A deploy someone approves by name, and a check from outside that the site serves the version you
  shipped.
- One branch, one working copy and one written task per agent.
- After every incident: which check would have refused this? Write that check.

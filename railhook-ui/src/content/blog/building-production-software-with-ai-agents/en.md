---
title: AI-written code you can trust: how a production webhook gateway was built with Claude Code
lead: An AI agent wrote nearly all of Railhook's code. What made that code worth deploying wasn't a better prompt. It was a repository that refuses wrong changes on its own, and a person who decided what "wrong" means.
description: A Claude Code case study with real git numbers. Eight practices for building production software with AI agents: ratchet tests, generated references, phone checks and deploy checks.
date: 2026-09-22
author: Vadym Kykalo
tags: [ai agents, claude code, engineering, testing, case study]
sourcesCheckedOn: 2026-09-19
---

On 22 August a review of the delivery pipelines turned up a bug that had been in production for
several releases. Every event bigger than 1 KB was being delivered, and HMAC-signed, as a
gzip+Base64 blob instead of JSON. The schema had a `payload_compressed` column and the API set it.
The worker's copy of the entity never mapped that column, so the worker never knew. Every test
passed.

I didn't write that bug, and I didn't write the fix. Claude Code wrote both, the same way it wrote
nearly all of Railhook over many rounds of me setting direction, reviewing and pushing back. I'm a
senior Java developer. My part was knowing what correct looks like for this kind of system.

The part of that fix worth writing about is the test it added. It fails the build whenever a
column exists in the schema and one service maps it while the other doesn't. That bug can't
happen again, and no prompt I could have written would make the same promise. That is the whole
argument of this article. **You can't review every line an agent writes. What you can do is build
a repository that refuses wrong changes on its own.**

## What the history shows

Every number here comes from the public repository, so each row says how it was measured.

| Measure | Value | How it was counted |
|---|---|---|
| First commit, first release | 15 Dec 2025, `v1.0.0` on 17 Dec | `git log --reverse`, `git tag` |
| Commits | 1,413, of which 1,088 are not merges | `git rev-list --count HEAD`, `--no-merges` |
| Releases | 60 tags, 42 of them in September 2026 | `git for-each-ref refs/tags` by creation date |
| Commits with a `Co-Authored-By: Claude` trailer | 648 of 1,088; 648 of the 743 made since 20 Aug 2026 | case-insensitive grep over each commit body |
| Code, tests excluded / test code | about 132,900 / 83,900 lines | `git ls-files`, without lockfiles, generated types, `openapi.yaml` or images |
| Test methods | 1,681 api · 348 worker · 228 common · 79 cli · about 880 UI cases | `@Test` / `@ParameterizedTest`; `it(` / `test(` |
| Ratchet guards | 22 classes, 56 test methods | files carrying `@Tag("ratchet")` |
| CI | 18 jobs in `ci.yml`, 37 across 10 workflows | job keys under `jobs:` |
| `fix:` vs `feat:` commits since 20 Aug | 331 vs 91 | subject prefix |

Two things this table doesn't say on its own. First, the trailer only starts on 20 August 2026,
in the same commit that added `CLAUDE.md`. The 345 commits before that carry no trailer and mostly
one-word messages: "fix" 115 times, "add feature" 72, "impl" 30. Git can't tell you who wrote them.
I'm telling you it was the agent too, and on that point you have only my word. There were also no
commits at all from April to July.

:::figure ai-commit-history

Second, look at the last row. Since the harness went in, fixes outnumber features more than three
to one. Much of that is the harness doing its job, as reviews and ratchets dig up bugs that were
already there. It is also the honest price of working this way. An agent produces code that looks
finished very quickly, and a lot of it is wrong at the edges.

## The loop, and who owns each step

:::figure ai-harness-loop

The agent is one box out of eight. A change starts as a task file that I wrote or approved. The
agent loads the rules for the part of the repository it is in and writes a failing test before the
code. Local checks and CI then either refuse the change, and it goes back to the agent, or let it
through. A release goes out only when a named person approves the deploy, and a check from outside
confirms the site is actually serving the new version. When something still gets through, it
becomes a new rule, skill or ratchet. That last arrow is what makes the loop worth having.

## 1. Write the rules down where the agent reads them, and expect them to fail

Claude Code loads `CLAUDE.md` at the start of a session. A `CLAUDE.md` in a subdirectory loads
when the agent reads a file there
([Claude Code docs, *How Claude remembers your project*](https://code.claude.com/docs/en/memory)).
Railhook has three: the root one, `railhook-ui/CLAUDE.md` and `railhook-docs/CLAUDE.md`. They
state rules together with the reason for each. From `CLAUDE.md`:

```text
- **Never hand-roll an org check.** `@TenantId` makes Hibernate scope every query to the caller's
  organization, `findById` included; a service method taking an `organizationId` fails the build.
```

Next to them sits `CONTEXT.md`, a glossary the agent has to use. Each term comes with the words
to avoid, because two names for one thing are how two implementations of one thing start. From
`CONTEXT.md`:

```text
**Delivery**:
The obligation to get one event to one endpoint, held until it succeeds or is abandoned.
Distinct from the individual tries it takes.
_Avoid_: send, dispatch, job
```

**What went wrong.** The early rule said "squash merge is preferred" and nothing more. A release
PR was squash-merged into `main`, which rewrote its commits under new hashes, and the merge for
release 2.3.0 then reported 19 conflicts, 12 of them between byte-identical files. The rule was
rewritten on 22 August. On 23 August another PR was squash-merged into `main` all the same. Now a ruleset on `main` blocks squash and rebase. Claude
Code's own documentation says as much: these files are "context, not enforced configuration."

**Adopt it.** Keep one short instructions file per area, with the reason next to each rule. If the
agent breaks a rule once, rewrite the rule. If it breaks it twice, turn it into a check.

## 2. Turn conventions into ratchets

A ratchet is a test that allows today's exceptions and refuses new ones. Railhook has 22 of them.
They check that native queries carry a tenancy predicate, that no service method takes an
organization id, that every mutating handler declares its access level and scope, that outbound
HTTP clients declare SSRF protection, that entities and schema agree, that applied migrations never
change, that the committed OpenAPI spec matches the served one, and that an upgrade restarts
services in the right order. This is the core of one, `ServiceTenantParameterTest`:

```java
// Vacuity guard: a scan that finds nothing would pass this test while checking nothing.
assertTrue(classesScanned >= 40,
        "Expected to scan at least 40 service classes, found " + classesScanned
                + " — the classpath scan is broken, not the code");
assertTrue(methodsScanned >= 300,
        "Expected to scan at least 300 public service methods, found " + methodsScanned);

assertEquals(Set.of(), offenders,
        "These service methods take an organization as a parameter. Org "
                + "ownership a property of data access: read TenantContext, or enter a scope "
                + "with TenantContext.runAs / @SystemTenant. If the organization genuinely "
                + "comes off a row rather than off the caller, add the method to "
                + "DOCUMENTED_EXEMPTIONS with a reason.");
```

It has three parts, and all three matter with an agent. There is a frozen exemption list where
every entry carries a reason. There is a vacuity guard, because a scan that silently finds nothing
passes. And there is a failure message that names the fix, because the next reader of that
message is the agent, and it follows it.

:::figure ai-ratchet-growth

**What went wrong.** A native `INSERT` into `usage_daily` fell outside Hibernate's tenant filter
and hit a `NOT NULL organization_id` at 00:05 every night. A catch block swallowed it. Now
`NativeQueryTenantPredicateTest` makes every native query declare which kind it is. The
`payload_compressed` bug from the opening became `EntityMappingParityIntegrationTest`.

**Adopt it.** Once you find a class of bug, write the check that makes the whole class fail the
build. Word its failure message as an instruction.

## 3. Put invariants where the change will be made

Railhook sends in two directions, and for a while the incoming pipeline was a copy of the outgoing
one. A review recorded in commit `2070d30` found three fixes that had been made on one side and never carried across.
One of them meant a slow database write after a `2xx` could trip the HTTP timeout, overwrite the
`SUCCESS` and schedule a duplicate forward. Now one `AttemptRunner` serves both directions, and its
javadoc is the list of things that went wrong before. From `AttemptRunner.java`:

```java
 * <p>Six invariants, each of which was once correct on one direction and wrong on the other:
 *
 * <ol>
 *   <li>No DB, Redis or Kafka work inside the reactive chain — a write there can trip the
 *       HTTP timeout and drive the failure path over a SUCCESS already written. The same rule
 *       applies after the chain: once a 2xx is in hand, nothing that goes wrong while writing
 *       it down may reclassify it as something to retry.</li>
 *   <li>No successor Attempt unless {@link AttemptStore#finalise} reports it wrote.</li>
 *   <li>Every path that takes a concurrency permit releases it, including those that throw
 *       before the request is built.</li>
 *   <li>A failed transformation never lets the raw payload out.</li>
```

The opposite also needs saying. Agents like symmetry, so `RetryLadderDefaults` says outright that
the two directions' retry ladders "differ deliberately and must not be 'fixed' into agreement."

**Adopt it.** The agent reads the file it is about to edit. Record what went wrong there, not in a
wiki it will never open.

## 4. Generate references instead of writing them

`openapi.yaml` is committed and semantically diffed against the spec the server actually serves.
The UI's API types are generated from it, and a compile-time contract fails the typecheck when the
hand-written mirror drifts. The docs' configuration reference is generated from `.env.dist`. The
endpoint table that used to be written by hand, a 4,000-line page that nothing kept in sync, is
gone.

**What went wrong.** An agent writes prose that sounds as if it has been checked. A backup script
promised a check, `make verify-backup-parity`, that existed in neither the Makefile nor CI. The
Helm README said Flyway runs in an init container that had already been removed. The roadmap said
alerting "already counts the condition" when nothing called `fireAlert`, so every alert rule a
user created did nothing. The upgrade helper's comment said the worker restarted last, and the
code started it first. Each was corrected, and the backup promise became a real check,
`BackupFlagParityTest`.

**Adopt it.** Anything a machine can derive, derive, and fail CI when it goes stale. Keep prose for
the *why*.

## 5. Tests first, and make each test land where it can run

The rule is one line of `CLAUDE.md`: "New behaviour is written test-first — a failing test stating
the expected result, then the implementation." Procedures that came up again and again became
skills, instructions the agent loads when a task matches them
([Claude Code docs, *Extend Claude with skills*](https://code.claude.com/docs/en/skills)).
`db-migration` covers the three-file rule for schema changes. `backend-tests` explains that a test
class's name decides its CI job, and `scripts/check-test-routing.sh` enforces it:

```bash
INTEGRATION_SUFFIXES='(IntegrationTest|IT|RepositoryTest|ConcurrencyTest|RbacTest|IsolationTest)\.java$'
NEEDS_DOCKER='@Testcontainers|@SpringBootTest|AbstractIntegrationTest|GenericContainer|PostgreSQLContainer|KafkaContainer'

while IFS= read -r file; do
    if [[ "$file" =~ $INTEGRATION_SUFFIXES ]]; then
        continue
    fi
    if grep -qE "$NEEDS_DOCKER" "$file"; then
        misrouted+=("$file")
    fi
done < <(find . -path ./node_modules -prune -o -path '*/src/test/java/*' -name '*Test.java' -print)
```

**What went wrong.** A Testcontainers test named `FooTest` passes on a laptop with Docker running
and fails in the unit job, where Docker isn't available, and it looks like a broken test rather
than a misnamed one. Separately, a repository-wide rename changed a comment inside an
already-applied migration and every other gate stayed green. That became `MigrationChecksumTest`.

**Adopt it.** Once you've explained a procedure to the agent twice, write it up as a skill.

## 6. Look at it on a phone, in a real browser

jsdom can't see a page that scrolls sideways, and it can't see iOS Safari zooming into a focused
field. The e2e suite in `railhook-ui/e2e/layout.spec.ts` loads every page on its list in
Chromium, at 390 px and at 1440 px, and fails on any field an iPhone would zoom into:

```javascript
async function smallFields(page: Page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll<HTMLElement>('input:not([type=hidden]):not([type=checkbox]):not([type=radio]), textarea, select'))
      .filter((el) => el.getBoundingClientRect().width > 0 && parseFloat(getComputedStyle(el).fontSize) < 16)
      .map((el) => `${el.tagName.toLowerCase()}#${el.id || el.getAttribute('name') || '?'} ${getComputedStyle(el).fontSize}`),
  );
}
```

**What went wrong.** The registration form was cut off at the right edge of an iPhone in
production. The test was written after that. On 19 September the same check caught a field on the
signature verifier page, but only once that page had been added to the test's list. A guard only
checks what it names.

**Adopt it.** Run a real browser at a phone width in CI, and keep its list of pages complete.

## 7. Verify the deploy from outside, and keep probing after

Deploying is a manual workflow gated by a named approver. The deploy key on the host can run
`deploy <tag>` and `status` and nothing else. After a deploy, the workflow checks the result from
the outside. From `.github/workflows/deploy-prod.yml`:

```bash
# A 200 is not the release. 2.17.0 answered 200 while the host still served the
# previous UI, pinned by an override, and this step called it deployed. The UI image
# serves its own version; the query string keeps any cache out of the answer.
want="${VERSION#v}"
served=""
for attempt in $(seq 1 12); do
  served=$(curl -fsS --max-time 10 "https://railhook.io/version.txt?deploy=${GITHUB_RUN_ID}-${attempt}" | tr -d '[:space:]' || true)
  [ "$served" = "$want" ] && break
```

**What went wrong.** On the 2.20.7 deploy the upgrade helper restarted the worker before the new
API had run its migrations. The worker failed schema validation, crashed once and came back, and
only the restart count showed it. `UpgradeOrderTest` now pins the order. Every night a k6 run
brings up the whole stack and fails if FIFO ordering breaks under a backlog.

**Adopt it.** A green pipeline isn't the release. Check the version the site is actually serving,
from outside.

## 8. Run agents in parallel, but keep them apart

Several agents often work at once, each on its own branch in its own git worktree. Each branch
gets one task file in `.claude/tasks/`, and `CLAUDE.md` calls that "the only one that authorizes writing code."
Proposals live in `.claude/features/` and are "not work orders." Shared files are where parallel
work collides, so translation keys arrive as hand-off files and a script merges them. The script
refuses a key that two agents define differently.

**Adopt it.** One branch, one worktree and one written task per agent, and a merge step for the
files they all touch.

## What still got through

:::figure ai-bug-guardrail

Between 13 and 18 September, 2.20.0 was followed by thirteen patch releases, and several of them
were security fixes. In one, an API key for one project could rotate the signing secret of another
project's endpoint in the same organization and get the new secret back. The tenancy ratchets
guard the *organization* boundary, and nothing guarded the *project* boundary inside one. In
another, a workflow node took a project id from its saved configuration, nothing checked it, and a
workflow could write events into another organization's project. The fixes came with tests,
including `ProjectResourceScopeIsolationTest` for every lookup that exists today. There is no
ratchet yet that refuses the *next* unscoped lookup.

A guardrail only refuses the bug it names. The next incident usually comes from the kind nobody
has named yet.

## What the human does

The repository shows where the human decisions are. `ARCHITECTURE.md` records failure modes and
scaling limits alongside the design. `ROADMAP.md` lists known gaps and what is deliberately not
planned, such as exactly-once delivery or a hosted-only tier. The deploy workflow calls deploying
"the one action here that is not reversible by reverting a commit", which is why a person
approves it. The agent proposes, and often proposes
well. Deciding scope, saying no, and approving what touches production stay with a person.

## The architecture, honestly

Some of it is strong. The event, its deliveries and the outbox row are written in one
transaction, so a client either gets a `201` and the work will happen, or gets an error and none
of it exists. Claim tokens fence off a worker's late write. Tenancy is enforced by Hibernate's
`@TenantId` rather than by checks someone has to remember. Ordering is per endpoint, and the
nightly probe tests it.

Some of it isn't ideal. A poller announces the outbox, not change-data-capture: one fewer moving
part, paid for with polling. The API and the worker keep separate copies of shared entities. That
decision was kept, and it has already cost the two production bugs a ratchet now guards against.
Delivery is at-least-once by design. There is no trace export. A rolling upgrade with two versions
running at once is untested. And there is no written procedure for reconciling Postgres, Kafka and
Redis after a restore from backup. The repository's own docs list every one of these.

## A checklist to start with

- One short instructions file per area of the code, with the reason next to each rule.
- A glossary with an "avoid" line for every term.
- Tests first, with the test's name deciding where it runs.
- A ratchet for every class of bug you find: frozen exemptions, a vacuity guard, a failure
  message written as an instruction.
- Generated references (API spec, types, config) with a CI check for drift.
- Invariants in the javadoc of the file where the next change will be made.
- A real browser at a phone width in CI.
- A manual deploy with a named approver, and a check from outside that the served version is the
  one you shipped.
- One worktree and one task file per agent.
- After every incident, ask which check would have refused it, and write that check.

Everything above is in the [Railhook repository](https://github.com/vadymkykalo/railhook), MIT
licensed, harness included.

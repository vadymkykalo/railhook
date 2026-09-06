# CLAUDE.md

Guidance for Claude Code working in this repository.

Java 17 + Spring Boot 3.5 (Maven reactor: `common`, `api`, `worker`, `cli`), React + Vite +
TypeScript in `railhook-ui`. `api` owns all Flyway migrations.
`docs/ARCHITECTURE.md` has the architecture and the sequence diagrams for both pipelines.

## Commands

`make help` lists every target. `make up` also creates `.env` from `.env.dist` and the Kafka
topics — don't do either by hand. There is one `docker-compose.yml` (published images, what
`install.sh` deploys) plus `docker-compose.build.yml`, a small overlay adding build contexts;
put a service change in the former, never in both.

`make dev-api` / `dev-worker` / `dev-ui` rebuild one service and restart it:
the fast inner loop once the stack is up.

```bash
mvn clean compile -B                # what CI compiles with
make test-ui                        # frontend unit tests (Vitest)
npm run lint && npm run typecheck   # in railhook-ui/, both gate CI
```

For running or writing Java tests, use the `backend-tests` skill — the test class name decides
which CI job the test lands in, and `scripts/check-test-routing.sh` fails the build on a
Docker-dependent test named so it routes to the no-Docker unit job.

## Checks that fail CI for reasons that aren't in the diff

Many are *ratchets*: they demand the known exception list stop growing, not that the codebase be
perfect. Adding to a documented-exemption list is a review decision with a stated reason, never a
way to get green.

**`make ratchets` is the live set** — every guard carries `@Tag("ratchet")`, so ask the build
rather than a list here. Each failure names its remedy. Three whose remedy nobody guesses:

- **`openapi.yaml` is committed and semantically diffed** against the spec springdoc serves. After
  an intentional API change, regenerate rather than hand-edit:
  `mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`.
  A backend DTO change then also lands in the UI: `npm run types:generate` regenerates
  `src/types/api.generated.ts` (`make types-check` mirrors CI), and `src/types/api.contract.ts`
  fails the typecheck until the hand-written mirror in `api.types.ts` agrees with it again.
- **The in-app API reference is generated, not written.** `src/pages/docs/api-index.generated.json`
  is derived from `openapi.yaml` and committed; `make docs-check` (and CI) fails when it is
  stale. Regenerate with `cd railhook-ui && npm run docs:api-index`. The guides under
  `src/pages/docs/` stay hand-written — they explain *why*, which a spec cannot. Never
  hand-write an endpoint table: that is what the 4,000-line page this replaced was, and nothing
  kept it in sync.
- **The version lives in seven places** — reactor pom, `deploy/helm/railhook/Chart.yaml` (version
  *and* appVersion), `railhook-ui/package.json`, all three SDK manifests under `sdks/`.
  Never bump one by hand: `make version-set VERSION=2.4.0`; `make version-check` mirrors CI.
- **Per-module JaCoCo ratchets** bind to `verify`, and CI runs them only after merging the unit
  *and* integration exec files. `mvn verify` over a partial test selection trips them against
  partial data — use `mvn test` for ordinary work.

## Git workflow (GitFlow)

Two rules carry the rest: **never commit or push directly to `main`**, and **never open a
`feature/*` PR against `main`**.

| Branch | Cut from | Merges into | Purpose |
|--------|----------|-------------|---------|
| `main` | — | — | Production-ready. Only ever receives `release/*` and `hotfix/*` |
| `develop` | `main` | `release/*` | Integration branch — the default working branch |
| `feature/*` | `develop` | `develop` | New work |
| `release/*` | `develop` | `main` **and** back into `develop` | Release preparation, version bumps |
| `hotfix/*` | `main` | `main` **and** back into `develop` | Production fixes |

- Branch as `feature/<short-kebab-description>` from an up-to-date `develop`. A hotfix branches
  from `main`, not `develop` — branching it from `develop` drags unreleased work into production.
- Release: `release/1.x.0` from `develop` → `make version-set VERSION=…` → PR to `main` → tag
  `v1.x.0` after merge → merge back into `develop`.
- **Anything that lands on `main` must be merged back into `develop`**, or the fix disappears at
  the next release. That back-merge is local (`git checkout develop && git merge origin/main`),
  not a PR.
- Merge style: `feature/*` → `develop` is **squash** by default (`--no-ff` when the branch carries
  two or more separately-meaningful commits). `release/*` / `hotfix/*` → `main` is **a merge
  commit — never squash, never rebase**: both rewrite the branch into new SHAs with no shared
  ancestry, so `main` and `develop` lose their common base and every later merge conflicts on
  byte-identical files. This has happened twice; a ruleset on `main` now blocks squash and rebase.

`main` requires a PR with green checks and merge commits only; reviews are a convention, not a
gate. `develop` blocks only force-push and deletion. Commit prefixes: `feat:`, `fix:`, `docs:`,
`test:`, `refactor:`, `chore:` (`CONTRIBUTING.md` has the policy).

## Conventions

- **New behaviour is written test-first** — a failing test stating the expected result, then the
  implementation (`tdd` skill for the loop, `backend-tests` for the class name). Refactors,
  `docs:` and `chore:` are exempt.
- **`CONTEXT.md` is the domain model** — Event, Delivery, Forward, Claim, Attempt, Deferral,
  Source, Destination, each with an `_Avoid_:` line of near-synonyms not to use. Read it before
  naming a class, column, metric or UI string. Where the code and `CONTEXT.md` disagree, one of
  them is a bug — it is not a licence to pick either word.
- **A schema change touches three places**: the JPA entity in `api`, its copy in `worker`, and a
  migration. Use the `db-migration` skill; a column left unmapped on either side fails the build.
- **Never hand-roll an org check.** `@TenantId` makes Hibernate scope every query to the caller's
  organization, `findById` included; a service method taking an `organizationId` fails the build.
  What that leaves you responsible for — a scope for work without a request, entering it outside
  the transaction, native queries, your own thread pools — is enforced by the ratchets, which say
  what to do when they fail.
- **A fix to attempt behaviour belongs in `AttemptRunner`, not in one direction.** Read its
  javadoc first: it enumerates invariants that were each once a real duplicate-delivery or
  stuck-throttle bug. Same for `RetryLadderDefaults` — the two directions' ladders differ on
  purpose; don't "fix" that into agreement.
- Config is env-var-driven: document every variable in `.env.dist`, consumed through
  `docker-compose.yml` into `application.yml`. `ProductionSafetyValidator` /
  `SecurityConfigValidator` fail startup on unsafe production config.
- `.claude/features/` (proposals — **not** work orders) and `.claude/tasks/` (one file per branch,
  the only one that authorizes writing code) are gitignored scratch; each has a README with its
  format. A task file is deleted when its branch merges.
- `railhook-ui/CLAUDE.md` carries the frontend conventions and loads automatically in that
  directory — add UI rules there, not here.
- Operational procedures: `docs/OPERATIONS.md`.

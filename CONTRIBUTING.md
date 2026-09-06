# Contributing to Webhook Platform

Thank you for your interest in contributing! This document outlines the guidelines and workflow for contributing to the project.

## Branch Strategy (GitFlow)

```
main ─────────────────────────────────────────► (production releases)
  │
  └── develop ────────────────────────────────► (integration branch)
        │
        ├── feature/add-retry-logic ──────────► (feature branches)
        ├── feature/dashboard-charts
        │
        └── release/1.1.0 ────────────────────► (release preparation)
```

### Branches

| Branch | Purpose |
|--------|---------|
| `main` | Production-ready code. Only merged from `release/*` or hotfixes |
| `develop` | Integration branch. All features merge here first |
| `feature/*` | New features. Branch from `develop`, merge back to `develop` |
| `release/*` | Release preparation. Branch from `develop`, merge to `main` and `develop` |
| `hotfix/*` | Production fixes. Branch from `main`, merge to `main` and `develop` |

## Development Workflow

### 1. Start a new feature

```bash
git checkout develop
git pull origin develop
git checkout -b feature/your-feature-name
```

### 2. Make your changes

- Write clean, tested code
- Follow existing code style
- Add tests for new functionality
- Update documentation if needed

### 3. Commit your changes

```bash
git add .
git commit -m "feat: add your feature description"
```

**Commit message format:**
- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation changes
- `test:` - Test changes
- `refactor:` - Code refactoring
- `chore:` - Build/tooling changes

### 4. Push and create PR

```bash
git push origin feature/your-feature-name
```

Create a Pull Request to `develop` branch on GitHub.

### 5. Code Review

- All PRs require at least one approval
- CI must pass before merging
- **Squash merge for `feature/*` → `develop`**, so a feature's work-in-progress
  commits land as a single commit
- **A merge commit for `release/*` and `hotfix/*` → `main` — never squash or
  rebase.** Squashing collapses the branch into a new SHA with no shared
  ancestry, so `main` and `develop` lose their common base and the mandatory
  back-merge conflicts on every file either side has touched — including files
  whose contents are identical. Release 2.3.0 hit this: the previous
  develop→main PR was squash-merged, and the next release's merge reported 19
  conflicts, 12 of them between byte-identical files.

## Getting set up

**You need:** Java 17, Maven 3.9+, Node 22+, Docker with Compose v2.

```bash
git clone https://github.com/vadymkykalo/railhook.git
cd railhook
make up          # builds all three images and starts everything
make help        # every other target
```

`make dev-api` / `dev-worker` / `dev-ui` rebuild and restart one service — the
fast loop once the stack is up.

## Running tests

The **class-name suffix decides which CI job a test lands in**, and there are no
tags or profiles doing it — `*IntegrationTest`, `*IT`, `*RepositoryTest`,
`*ConcurrencyTest`, `*RbacTest` and `*IsolationTest` go to the Docker job, and
everything else to the no-Docker one. Name a Docker-needing test wrong and it
passes on your machine and fails in CI; `scripts/check-test-routing.sh` fails
the build to stop that.

```bash
# Backend, no Docker needed — the same exclusion list CI uses
mvn test -Dtest='!*IntegrationTest,!*IT,!*RepositoryTest,!*ConcurrencyTest,!*RbacTest,!*IsolationTest'

# Backend, needs Docker (Testcontainers)
mvn test -Dtest='*IntegrationTest,*IT,*RepositoryTest,*ConcurrencyTest,*RbacTest,*IsolationTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# One class, or one method. Always scope with -pl: the reactor is multi-module.
mvn test -pl railhook-api -am -Dtest=TunnelServiceTest

# Frontend
make test-ui
cd railhook-ui && npm run lint && npm run typecheck
```

Use `mvn test`, not `mvn verify`, for ordinary work: the per-module JaCoCo
thresholds bind to `verify` and will fail against a partial test selection.

New behaviour is written **test-first** — a failing test stating the expected
result, then the implementation. Refactors, `docs:` and `chore:` are exempt.

## The checks that fail CI for reasons that are not in your diff

Most of these are *ratchets*: they require the known exception list to stop
growing, not that the codebase be perfect. Every one names its own remedy when
it fails. Adding to a documented-exemption list is a review decision with a
stated reason, never a way to get green.

```bash
make ratchets        # the build guards — run these before pushing
make version-check   # the version lives in seven files; only `make version-set` moves it
make types-check     # src/types/api.generated.ts is generated from openapi.yaml
make docs-check      # the in-app API reference is generated from openapi.yaml
```

Two whose remedy nobody guesses:

- **Changed a DTO?** `openapi.yaml` is committed and semantically diffed against
  what springdoc serves. Regenerate rather than hand-edit:
  `mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`.
  Then `cd railhook-ui && npm run types:generate`, and fix whatever
  `src/types/api.contract.ts` reports.
- **Changed the API surface?** `npm run docs:api-index` regenerates the in-app
  reference. Never hand-write an endpoint table — that is what the 4,000-line
  page this replaced was, and nothing kept it in sync.

**A schema change touches three places**: the JPA entity in `api`, its copy in
`worker`, and a Flyway migration. A column left unmapped on either side fails
the build.

**Never hand-roll an organization check.** `@TenantId` scopes every Hibernate
query to the caller's organization, `findById` included. A service method taking
an `organizationId` fails the build.

**Read `CONTEXT.md` before naming anything.** It is the domain vocabulary, and
every term carries a list of near-synonyms deliberately not used. Where the code
and `CONTEXT.md` disagree, one of them is a bug — it is not licence to pick
either word.

## Code Style

### Java (Backend)
- Follow standard Java conventions
- Use Lombok where appropriate
- Write meaningful test names

### TypeScript (Frontend)
- ESLint + Prettier enforced
- Functional components with hooks
- Type everything explicitly

## Releasing

Maintainers only — see [`docs/RELEASING.md`](docs/RELEASING.md).

## Questions?

Open an issue or start a discussion on GitHub.

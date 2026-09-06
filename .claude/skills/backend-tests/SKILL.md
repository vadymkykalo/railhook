---
name: backend-tests
description: Run or write Java tests in this repo (railhook-api/worker/common/cli). Use when running mvn tests, picking a name for a new test class, debugging why a test needs Docker, or reproducing a CI test failure locally.
---

# Backend tests

Test classes are routed to a CI job by their **class-name suffix** — there are no profiles, tags, or annotations doing this. The split is defined twice in `.github/workflows/ci.yml` (once per job) as inverse `-Dtest=` filters, so the two sets must stay complementary.

| Suffix | CI job | Needs Docker |
|--------|--------|--------------|
| `*IntegrationTest`, `*IT`, `*RepositoryTest`, `*ConcurrencyTest`, `*RbacTest`, `*IsolationTest` | Backend Integration Tests | yes (Testcontainers) |
| everything else (`*Test`) | Backend Tests | no |

**Naming a new test is therefore a routing decision.** A plain `*Test` that needs a database will pass locally (where Docker is up) and fail in the unit-test job. Conversely, giving a pure unit test an integration suffix makes CI spin up Postgres for nothing.

## Running

```bash
# unit tests (no Docker needed)
mvn test -Dtest='!*IntegrationTest,!*IT,!*RepositoryTest,!*ConcurrencyTest,!*RbacTest,!*IsolationTest'

# integration tests (Testcontainers → needs Docker)
mvn test -Dtest='*RepositoryTest,*IntegrationTest,*IT,*ConcurrencyTest,*RbacTest,*IsolationTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# single class / single method — always scope with -pl, the reactor is multi-module
mvn test -pl railhook-api -Dtest=TunnelServiceTest
mvn test -pl railhook-api -Dtest='TunnelServiceTest#createsSession'
```

CI runs both jobs with `--fail-at-end`, so a local run that stops at the first failure can hide later ones.

## Writing integration tests

Extend `com.webhook.platform.api.AbstractIntegrationTest` (`railhook-api/src/test/java/.../AbstractIntegrationTest.java`) rather than assembling `@SpringBootTest` by hand. It provides a Testcontainers PostgreSQL instance and, importantly, **excludes Kafka and Redisson autoconfiguration** and `@MockBean`s the infrastructure services that would otherwise reach for them — `OutboxPublisherService`, `SequenceGeneratorService`, `RedisRateLimiterService`, `TokenBlacklistService`, `RedisTunnelCoordinator`, and others.

Consequence: in these tests nothing is actually published to Kafka and no Redis state is real. A test that asserts on delivery dispatch, rate limiting, or sequence numbers is asserting on a mock — stub it explicitly or the assertion is vacuous. Test the outbox *rows* written in the transaction instead of the Kafka publish.

The class is annotated `@DirtiesContext(AFTER_CLASS)`, so each integration test class pays a full context restart. Group related scenarios into one class rather than splitting across many.

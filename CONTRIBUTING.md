# Contributing

You need Java 17, Maven 3.9+, Node 22+ and Docker with Compose v2.

```bash
make up            # build the images and start the stack
make help          # every other target
mvn test -Dtest='!*IntegrationTest,!*IT,!*RepositoryTest,!*ConcurrencyTest,!*RbacTest,!*IsolationTest'   # no Docker
mvn test -pl railhook-api -am -Dtest=TunnelServiceTest    # one class
make test-ui && (cd railhook-ui && npm run lint && npm run typecheck)
make ratchets version-check types-check docs-check        # the guards CI runs
```

- The test class name picks the CI job: `*IntegrationTest`, `*IT`, `*RepositoryTest`,
  `*ConcurrencyTest`, `*RbacTest` and `*IsolationTest` run with Docker, the rest without.
- Use `mvn test`, not `mvn verify`: JaCoCo thresholds on `verify` fail on a partial test run.
- After an API change, regenerate `openapi.yaml` with
  `mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`,
  then `cd railhook-ui && npm run types:generate`.
- Versions change only through `make version-set VERSION=x.y.z`.
- Branch `feature/<name>` from `develop` and open the PR against `develop`, never `main`.
  `main` only takes `release/*` and `hotfix/*`, as merge commits.
- Commit prefixes: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`.
- New behaviour comes with a test written first. Read [`CONTEXT.md`](CONTEXT.md) before naming anything.
- Report vulnerabilities privately, see [`SECURITY.md`](SECURITY.md).

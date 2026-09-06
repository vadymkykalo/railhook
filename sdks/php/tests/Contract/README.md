# PHP SDK contract tests

Runs the real `Railhook\Railhook` client against a REAL, running API instance
— not the stubbed cURL responses `tests/*Test.php` uses. The point is to
catch drift between this SDK and the API (a field renamed, a status code
changed, a new required field) that stubbed-response unit tests are
structurally unable to see.

## Running

```bash
# from the repo root
make up && make wait-healthy
cd sdks/php
composer install
composer test:contract
```

`CONTRACT_API_BASE_URL` overrides the target (default `http://localhost:8080`).
Reachability is probed with an intentionally invalid `POST /api/v1/auth/login`
(permitAll unconditionally), **not** `/v3/api-docs`: springdoc is only exposed
when `SWAGGER_ENABLED=true`, and `.env.dist` ships it `false`, so probing it
reported a perfectly healthy stack as unreachable and skipped this whole suite.

For a full end-to-end walk of the workflow that fails loudly instead of
skipping, see `scripts/live-api-smoke.php`.

If the API isn't reachable, every test is marked skipped (via
`ClientContractTest::setUpBeforeClass()` + `skipIfApiUnreachable()`) rather
than failed — this suite is meant to run where a live instance is guaranteed
(CI's `load-and-contract-tests.yml` workflow, or a developer with `make up`
running locally). Because `phpunit.xml`'s default `tests/` suite also
includes `tests/Contract`, plain `composer test` runs (and gracefully skips)
these too — `composer test:contract` (or `phpunit -c phpunit.contract.xml`)
is only needed to run *just* the contract suite.

## What's covered

Each test uses the class's `self::$ctx` (bootstrapped once in
`setUpBeforeClass()` — see `ContractSupport.php`, same pattern as the
node/python suites and `load/lib/setup.js`):

- `endpoints->create()` response shape matches what the SDK expects
- `subscriptions->create()` response shape matches what the SDK expects
- `events->send()` accepted and correctly fans out (`deliveriesCreated`)
- `deliveries->list()` returns the paginated shape expected
- an invalid API key is rejected as an `AuthenticationException`

The PHP SDK returns raw associative arrays rather than typed DTOs (unlike
node/python), so these tests assert on array keys/types directly rather than
comparing against a declared type — there's no PHP-side type declaration to
compare against for drift.

## Why not generate this from OpenAPI

See `sdks/node/tests/contract/README.md`'s "Why not generate this from
OpenAPI" — same reasoning: a spec-generated
contract tests possible; until then this hand-written suite against a live
instance is the documented fallback.

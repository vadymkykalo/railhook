# Node SDK contract tests

Runs the real `Railhook` client (`src/index.ts`) against a REAL, running API
instance — not the stubbed HTTP mocks `src/__tests__/*` uses. The point is to
catch drift between this SDK and the API (a field renamed, a status code
changed, a new required field) that stubbed-response unit tests are
structurally unable to see.

## Running

```bash
# from the repo root
make up && make wait-healthy
cd sdks/node
npm run test:contract
```

`CONTRACT_API_BASE_URL` overrides the target (default `http://localhost:8080`).
Reachability is probed with an intentionally invalid `POST /api/v1/auth/login`
(permitAll unconditionally), **not** `/v3/api-docs`: springdoc is only exposed
when `SWAGGER_ENABLED=true`, and `.env.dist` ships it `false`, so probing it
reported a perfectly healthy stack as unreachable and skipped this whole suite.
If the API isn't reachable, every test logs a warning and passes trivially —
this suite is meant to run where a live instance is guaranteed (CI's
`load-and-contract-tests.yml` workflow, or a developer with `make up` running
locally), not to silently fail a laptop that doesn't have the stack up.

## What's covered

Each test bootstraps its own throwaway user/org/project/API key (see
`support.ts` — same pattern as `load/lib/setup.js` in the k6 harness) so
tests don't share state or need fixtures:

- `endpoints.create` response shape matches the `Endpoint` type
- `subscriptions.create` response shape matches `Subscription`, including the
  `transformationId` / `transformationName` pair the API sends
- `events.send` accepted and correctly fans out (`deliveriesCreated`)
- `deliveries.list` returns the paginated shape `PaginatedResponse` expects
- an invalid API key is rejected as a 401 `AuthenticationError`

For a full end-to-end walk of the workflow — bootstrap, endpoint, subscription,
event, deliveries, attempts, incoming, error envelopes, and a signature the
running server itself produced — see `scripts/live-api-smoke.mjs`
(`npm run smoke:live`), which fails loudly instead of skipping.

## Why not generate this from OpenAPI

Generating these expectations from the committed OpenAPI spec (or running a
contract-diff tool against it) is strictly better than this file's
hand-written assertions — it catches drift at spec-review time instead of
only when this suite happens to run, and it stays in sync automatically as
the API's DTOs change. Until then, this hand-written suite against a live
instance is the documented fallback.

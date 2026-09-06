# Python SDK contract tests

Runs the real `railhook.Railhook` client against a REAL, running API instance
— not the stubbed `requests` mocks `tests/test_*.py` uses. The point is to
catch drift between this SDK and the API (a field renamed, a status code
changed, a new required field) that stubbed-response unit tests are
structurally unable to see.

## Running

```bash
# from the repo root
make up && make wait-healthy
cd sdks/python
pytest tests/contract -v
```

`CONTRACT_API_BASE_URL` overrides the target (default `http://localhost:8080`).
Reachability is probed with an intentionally invalid `POST /api/v1/auth/login`
(permitAll unconditionally), **not** `/v3/api-docs`: springdoc is only exposed
when `SWAGGER_ENABLED=true`, and `.env.dist` ships it `false`, so probing it
reported a perfectly healthy stack as unreachable and skipped this whole suite.

For a full end-to-end walk of the workflow that fails loudly instead of
skipping, see `scripts/live_api_smoke.py`.

If the API isn't reachable, every test is skipped (via the session-scoped
`contract_ctx` fixture in `conftest.py`) rather than failed — this suite is
meant to run where a live instance is guaranteed (CI's
`load-and-contract-tests.yml` workflow, or a developer with `make up`
running locally). Tests are also tagged with the `contract` pytest marker
(registered in `pytest.ini`), so `pytest -m contract` / `pytest -m "not
contract"` work if you want to filter explicitly.

## What's covered

Each test uses the session-scoped `contract_ctx` fixture (see `support.py` —
same bootstrap pattern as `sdks/node/tests/contract/support.ts` and
`load/lib/setup.js`) to register a throwaway user/org/project/API key once
per test run:

- `endpoints.create` response shape matches the `Endpoint` dataclass
- `subscriptions.create` response shape matches `Subscription`
- `events.send` accepted and correctly fans out (`deliveries_created`)
- `deliveries.list` returns the `PaginatedResponse` shape expects
- an invalid API key is rejected as a 401 `AuthenticationError`

## Why not generate this from OpenAPI

See `sdks/node/tests/contract/README.md`'s "Why not generate this from
OpenAPI" — same reasoning: a spec-generated
contract tests possible; until then this hand-written suite against a live
instance is the documented fallback.

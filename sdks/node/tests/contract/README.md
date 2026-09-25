# Node SDK contract tests

Run the real client against a running API to catch drift the stubbed unit tests cannot see.

```bash
make up && make wait-healthy   # from the repo root
cd sdks/node && npm run test:contract
```

`CONTRACT_API_BASE_URL` overrides the target (default `http://localhost:8080`). If the API is
unreachable, every test logs a warning and passes. Each test creates its own user, org, project
and API key.

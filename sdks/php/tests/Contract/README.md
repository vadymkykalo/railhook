# PHP SDK contract tests

Run the real client against a running API to catch drift the stubbed unit tests cannot see.

```bash
make up && make wait-healthy   # from the repo root
cd sdks/php && composer install && composer test:contract
```

`CONTRACT_API_BASE_URL` overrides the target (default `http://localhost:8080`). If the API is
unreachable, the tests are skipped. Each test creates its own user, org, project and API key.

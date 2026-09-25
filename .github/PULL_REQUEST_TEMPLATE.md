Target `develop`, not `main`.

## What and why

Closes #

## Checklist

- [ ] New behaviour has a test that failed before the change
- [ ] `make ratchets` passes
- [ ] DTO changed: `openapi.yaml` and `api.generated.ts` regenerated
- [ ] Schema changed: entity in `api`, its copy in `worker`, and a migration
- [ ] UI strings added to both `en.json` and `uk.json`

## How to test

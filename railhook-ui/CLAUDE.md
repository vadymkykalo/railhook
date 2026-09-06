# CLAUDE.md — railhook-ui

`src/api/*.api.ts` are thin wrappers over the shared `src/api/http.ts` axios client, which owns the bearer token and does automatic refresh-on-401 with request queueing — call the API through these wrappers rather than importing axios directly, or a 401 won't be retried.

TanStack Query keys are centralized in `src/api/queries.ts`; add new keys there instead of inlining string arrays at call sites.

Every user-facing string is a translation key with a value in **both** `src/i18n/locales/en.json`
and `uk.json`. `src/i18n/__tests__/locales.test.ts` fails CI on a key present in one and missing
from the other — otherwise the gap stays invisible until it renders as a raw key in production.

Page tests render through `src/test/renderPage.tsx`, not a bare `render()`: it supplies a fresh
no-retry `QueryClient` (retries hang error-state tests), a `MemoryRouter` and a fake
authenticated `OWNER`. One file: `npm test -- EndpointsPage`. `no-restricted-imports` enforces
this for `src/pages/**/*.test.tsx`; component tests outside `src/pages` are not covered, because
they have no route to render through.

### API types: three files, one of them hand-written

- **`src/types/api.generated.ts`** — generated from the committed `openapi.yaml` by
  `npm run types:generate`, and committed. `make types-check` (and CI) fails when it is stale.
  Never hand-edit it.
- **`src/types/api.types.ts`** — what the app imports. Still hand-written, and it has to be:
  springdoc marks nothing `required`, so every generated property is optional and consuming the
  generated types directly would put a null check on every field read.
- **`src/types/api.contract.ts`** — the join. Compile-time assertions that every mirrored
  interface still matches its schema, so `npm run typecheck` fails on a renamed, removed or
  retyped backend field. The mirror may be *narrower* than the spec (`role` omits `API_KEY`);
  it may not be wider, and it may not invent fields.

So a backend DTO change now lands in three steps: regenerate `openapi.yaml` on the backend side,
`npm run types:generate` here, then fix whatever `api.contract.ts` reports. It is not yet
exhaustive — a *new* backend field the mirror never learns about still goes unnoticed, since the
mirror is deliberately not required to cover everything.

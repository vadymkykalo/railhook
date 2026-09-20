# RESUME — feature/transformation-history

Checkpoint written because the machine is being shut down. **Delete this file before the branch
is reviewed** — it is scratch, not part of the change.

## What the branch does

`Transformation.version` was a counter with nothing behind it: the UI showed "v3" and no previous
template was stored anywhere. The branch stores every published template, and adds the read,
compare and restore that the number was already promising.

## Done and committed

| SHA | What |
|---|---|
| `c5887fa` | `feat:` template-language parity corpus (`railhook-common`) + a test in each of api and worker |
| `24bcfa8` | `feat:` backend — migration V083, entity, repository, service, four endpoints, `AuditAction.RESTORE`, `JsonDiffCalculator` extracted from `EventDiffService` |
| `c6ef5b2` | `feat:` UI — `TransformationHistoryPage`, route, links from the transformations list, en/uk strings, page test |
| `b39d4ab` | `docs:` version history on the transformations page, EN + UK |
| `07293c2` | `feat:` `RESTORE` in the audit log's action filter |
| *(this one)* | `wip:` retention pruning loads only version numbers — see below |

## The WIP commit (the only half-done thing)

Two files, an efficiency change to the retention cap, **written and compiling but not yet proven
by a test run**:

- `railhook-api/.../domain/repository/TransformationVersionRepository.java` — adds
  `findVersionNumbersDesc` (a JPQL projection) and `findByTransformationIdAndVersionLessThan`.
- `railhook-api/.../service/TransformationService.java` — `publishVersion` now finds the cut with
  the number projection and deletes only the expired rows, instead of loading the whole history
  (up to 50 × 64 KB of template text) to drop one row.

`mvn -o -q -pl railhook-api -am test-compile` passed after the change. The test that covers it is
`TransformationVersionHistoryIntegrationTest#historyIsCapped`, which passed against the *previous*
implementation of the same behaviour. Traced by hand against the new code and it holds (cap 5,
eight publishes → keeps v8…v4, `GET …/versions/1` → 404), but it has not actually been run.

**If in doubt, this commit can be reverted with no loss of behaviour** — the version before it was
green.

## Next three steps

1. `mvn test -pl railhook-common,railhook-api,railhook-worker -am --fail-at-end`. A full clean run
   was in progress at shutdown and had reached 274 test classes with zero failures; it never
   printed its summary. Nothing else is outstanding on the backend.
2. If `historyIsCapped` is red, revert the WIP commit rather than patching it.
3. Delete this file and re-run `make ratchets` (it passed at `24bcfa8`, and the WIP commit touches
   no ratcheted surface) before opening the PR into `develop`.

## Decisions already made — do not re-litigate

**API shape**, all under `/api/v1/projects/{projectId}/transformations/{id}`:

- `GET /versions` → `TransformationVersionResponse[]`, newest first. An index: `template` is
  omitted, because fifty 64 KB templates is not one response. Carries `version`, `current`,
  `restoredFromVersion`, `createdBy`, `createdByEmail`, `createdAt`.
- `GET /versions/{version}` → the same object **with** `template`.
- `GET /versions/diff?left=&right=` → `TransformationVersionDiffResponse`: both templates whole
  plus `JsonDiffEntry[]`, computed by `JsonDiffCalculator` — the algorithm lifted unchanged out of
  `EventDiffService`, since a template is JSON too. `EventDiffResponse.DiffEntry` was promoted to
  the top-level `JsonDiffEntry` and is now shared; the wire shape did not change.
- `POST /versions/{version}/restore` → `TransformationResponse`. `READ_WRITE` + `AccessLevel.WRITE`,
  audited as `RESTORE`. Restoring the current version is a 400.

**Restore is not an undo.** It publishes the old template again as the *next* version, marked with
`restoredFromVersion`, and leaves everything published since in place. Rewinding would make the
record of which template was live at a past moment disagree with what was.

**Retention: keep the newest 50 per transformation**, `TRANSFORMATION_HISTORY_LIMIT`, documented in
`.env.dist` and wired through `docker-compose.yml` → `application.yml`
(`transformations.version-history-limit`). Capped rather than unbounded because a template is up to
64 KB, an edit is one click, and nothing else prunes that table; 50 rather than 5 because a
rollback reaches for one of the last few and 50 is still under 3 MB worst case. The oldest fall off
first, and the version in use never can (the limit is floored at 1 in the constructor).

**The version counter no longer moves for a save that leaves the template byte-identical.** The
edit form PUTs the whole transformation back, so renaming used to count as rewriting; with a
history behind the counter that would fill it with entries nobody made.

**The two template-language implementations were not merged.** They differ on purpose on a
malformed path — null in the api so a half-typed template still previews, throws in the worker so a
signed body never ships with a hole in it — and unifying them would be a change to delivery
behaviour. `TemplateLanguageConformance` in `railhook-common` pins everything they *do* agree on
(17 cases) and each module's `TemplateLanguageParityTest` runs it; each also pins the one agreed
disagreement.

## Verified before the shutdown

`make ratchets` green · `make types-check` green · `make docs-check` green (env reference
regenerated, EN/UK parity, links validator) · `openapi.yaml` regenerated and
`OpenApiDriftIntegrationTest` green · `api.generated.ts` regenerated, mirror and contract updated ·
UI `lint`, `typecheck` green · `npx vitest run` — 1108 passed, one unrelated flake in
`blogSwitch.test.tsx` that passes on its own · the four new backend test classes green · the UI
checked in the browser at 1440 and 375, screenshots in `.playwright-mcp/` (gitignored).

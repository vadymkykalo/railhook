---
name: db-migration
description: Change the database schema in this repo — add or alter a table or column, write a Flyway migration, or fix a Hibernate schema-validation startup failure. Use whenever a change touches a JPA entity or the db/migration directory.
---

# Database migrations

## Where migrations live

Only in `railhook-api/src/main/resources/db/migration`, named `V0NN__snake_case_description.sql`. The API module is the sole owner: `spring.flyway.enabled: true` is set there and the worker has **no Flyway dependency at all**. The worker connects to the same schema and expects it to already be migrated.

Append the next free number (currently through `V068`); never edit a migration that has been applied anywhere — Flyway records a checksum per version and refuses to start when it changes. To correct an applied migration, add a new one that fixes it forward.

`MigrationChecksumTest` (a `@Tag("ratchet")` guard) enforces that: it holds a committed hash of every migration and fails when one changes. Adding a migration therefore also means regenerating the manifest and committing it alongside:

```bash
mvn test -pl railhook-api -am -Dtest=MigrationChecksumTest -Dmigrations.regenerate=true
```

The two diffs read differently on purpose. A new migration adds a line to `src/test/resources/db/migration-checksums.txt`; an edit to an existing one changes a line and adds nothing — which is the review signal. It exists because a repository-wide rename once changed a comment in an applied migration and every other gate stayed green.

Existing files use plain SQL with a leading comment explaining *why*, and `COMMENT ON TABLE` for non-obvious tables. Follow that.

## The two-entity rule

`railhook-api` and `railhook-worker` each keep their **own JPA entity and repository copies** of the shared tables — `Event`, `Delivery`, `Endpoint`, `IncomingEvent`, `IncomingSource`, `IncomingDestination`, `IncomingForwardAttempt`, `DeliveryAttempt`, `OrderingCursor`, `Transformation`. They are not shared through `railhook-common`.

So a schema change is usually **three edits**: the migration, the API entity, and the worker entity. Check both `domain/entity` directories before assuming a table is API-only.

## Why a missed copy breaks at startup, not at query time

Both services run `spring.jpa.hibernate.ddl-auto: validate`. Hibernate compares every mapped entity against the live schema during startup and aborts the context if a column is missing or mistyped. Effects worth knowing:

- Adding a `NOT NULL` column without a default breaks **currently running** instances of both services during a rolling deploy, since the old code inserts without it. Add nullable (or with a default), backfill, tighten in a later migration.
- Updating only the API entity leaves the worker fine, but updating only the worker entity — or renaming a column — takes down whichever service was missed on its next restart, with a validation error rather than a runtime error.
- A dropped column fails validation in any service still mapping it, so drops need the entity removed and deployed first.

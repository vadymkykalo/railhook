# Data retention and export

Railhook stores every Event, every Attempt and every Incoming Event as it arrived. Two of those
tables are the largest in the database by an order of magnitude, and retention is the only thing
that bounds them. This is also the machinery that answers a GDPR request.

## What is kept, and for how long

All of it is configurable through `.env`; the defaults are chosen so a self-hosted deployment
does not grow without bound while nobody is watching.

| Data | Variable | Default |
|---|---|---|
| Delivery Attempts (errors) | `DATA_RETENTION_ATTEMPTS_DAYS` | 90 days |
| Delivery Attempts (2xx) | `DATA_RETENTION_SUCCESSFUL_ATTEMPTS_DAYS` | **14 days** |
| Events | `DATA_RETENTION_EVENTS_DAYS` | 90 days |
| Incoming Events | `DATA_RETENTION_INCOMING_EVENTS_DAYS` | 30 days |
| Tunnel request log | — | 7 days |
| Attempts kept per Delivery | `DATA_RETENTION_MAX_ATTEMPTS_PER_DELIVERY` | 10 |
| Abandoned Outbox rows | `OUTBOX_DEAD_RETENTION_DAYS` | 90 days |

Three of these deserve their reasoning spelled out.

**Successful Attempts go first, and much sooner.** A 2xx Attempt is worth almost nothing after
the fact: you know it worked. A failed one is the whole debugging record. Deleting successes at
14 days and errors at 90 removes most of the bulk while keeping all of the evidence.

**Events default to the same 90 days as Attempts, on purpose.** Keeping a Delivery longer than
the Event it belongs to leaves you rows that point at nothing — the detail gone and the bulk
retained, which is the worst of both.

**Attempts per Delivery is a second, independent bound.** An endpoint that fails for a week
generates Attempts against one Delivery indefinitely. Age alone will not catch that; a cap per
Delivery will.

## When it runs

| Job | Schedule | Variable |
|---|---|---|
| Main cleanup | 02:00 daily | `DATA_RETENTION_CRON` |
| Per-Delivery attempt cap | every 30 min | `DATA_RETENTION_LIMIT_CRON` |
| Burst cleanup | every 4 hours | `DATA_RETENTION_BURST_CLEANUP_CRON` |
| Table size metrics | every 15 min | `DATA_RETENTION_TABLE_METRICS_INTERVAL_MS` |

Deletion is batched (`DATA_RETENTION_BATCH_SIZE`, default 1000) so a long-overdue first run does
not take a lock the whole platform waits behind.

`delivery_attempts` and `tunnel_request_log` are **partitioned by time in Postgres**. For those,
expiry is a `DROP TABLE` on a whole partition — O(1) — rather than a delete of millions of rows.
This is why the retention window and the partition granularity should not drift far apart.

## Watching it work

| Metric | Meaning |
|---|---|
| `delivery_attempts_table_rows`, `events_table_rows`, `incoming_events_table_rows` | Estimated size of each large table |
| `delivery_attempts_cleanup_total`, `events_cleanup_total`, `incoming_events_cleanup_total` | Rows removed |
| `partition_dropped_total` | Whole partitions expired |
| `partition_default_rows` | Rows that landed in the default partition — should be zero; anything else means a partition was missing when a write arrived |

If a table's row count climbs steadily while its cleanup counter stays flat, the job is not
running — check the cron expressions and the application logs at 02:00.

## Per-plan retention

`RetentionCleanupScheduler` can apply retention per billing plan. It does nothing while
`BILLING_ENABLED=false`, which is the self-hosted default — so on a self-hosted deployment the
`DATA_RETENTION_*` variables above are the only thing bounding the two largest tables. Do not
assume a plan is trimming them for you.

## Export and erasure

`GdprExportService` produces a complete export of one Organization's data as a single document —
the Organization, its Projects, Endpoints, Subscriptions, Sources, Destinations, Events,
Deliveries and Attempts. It is exposed through the API, is scoped by `@TenantId` like everything
else, and therefore cannot return another tenant's rows even if asked to.

Erasure is the same mechanism as retention, narrowed: deleting an Organization cascades through
the rows that hang off it.

For a request that names a single data subject rather than a whole tenant, there is no
field-level erasure tool — the honest answer is that retention is what removes the payload, on
the schedule above. **Do not reach for PII masking here.** Masking is applied when a payload is
*displayed* — in the dashboard, in an event diff, through a shared debug link — and changes
neither what is stored nor what is delivered. It limits who sees a value; it does not stop the
value being kept. If a field must never be stored, the place to drop it is before the Event is
sent to Railhook, or in a Transformation.

## Related

- **PII masking** — configured in the dashboard; the guide is in the app under `/docs`.
- **Backup and restore** — `OPERATIONS.md`. Note the known limitation recorded there: Postgres,
  Kafka and Redis can disagree about what has been delivered after a restore, and there is no
  written reconciliation procedure yet.

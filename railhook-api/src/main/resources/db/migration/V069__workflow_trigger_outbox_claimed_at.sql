-- When a workflow trigger row was handed to an executor, as distinct from when the event that
-- produced it was ingested.
--
-- reclaimStalledRows measured created_at, which is not a property of the run at all. A row is
-- deliberately held in PENDING while its project sits at its concurrency ceiling — that is what
-- deferToNextPoll exists for, and it is the mechanism that stops one project taking the whole
-- workflow pool — so a busy project's rows are routinely older than the stall threshold before
-- they are claimed for the first time. The sweep then returned them to PENDING while a live
-- executor was still running them, the next poll claimed them again, and the workflow ran
-- concurrently with itself, spending an attempt each round until the row was marked FAILED
-- having in fact executed several times.
--
-- Nullable with no backfill on purpose: rows already PROCESSING at upgrade time have no honest
-- value to give it, and the sweep treats a null claimed_at as "not yet claimed" so it leaves
-- them alone. They are collected by the daily cleanup instead. A NOT NULL column would also
-- break the running instances mid-deploy, which insert without it.
ALTER TABLE workflow_trigger_outbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN workflow_trigger_outbox.claimed_at IS
    'When claimBatch handed this row to an executor. Null until then. reclaimStalledRows measures staleness from here, never from created_at, which measures how long the row waited rather than how long the run has taken.';

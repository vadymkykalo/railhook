-- When a person last put a Delivery back on its Retry Ladder: a retry from Failed Messages, or a
-- resend of the Delivery itself.
--
-- The hard-cap escalation moves a PENDING Delivery to DLQ once it is older than the cap, and it
-- measured that age from created_at. A Delivery keeps its created_at when it is retried by hand,
-- so retrying one created more than four days ago put it straight back in Failed Messages at the
-- next sweep, usually before its first new attempt had gone out.
--
-- NULL means the Delivery has only ever walked the ladder it started with at created_at, which is
-- every existing row: the column is deliberately not backfilled, so adding it rewrites nothing.
-- The escalation query keeps its created_at predicate — this column is never earlier than
-- created_at — and uses this one only to spare rows put back to work since, so it needs no index.
ALTER TABLE deliveries ADD COLUMN ladder_resumed_at TIMESTAMP;

COMMENT ON COLUMN deliveries.ladder_resumed_at IS
    'When the Delivery was last put back on its Retry Ladder by hand; NULL if never. The hard-cap age is measured from here when set.';

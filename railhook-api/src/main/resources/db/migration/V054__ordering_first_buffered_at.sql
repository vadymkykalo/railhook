-- The FIFO gap timeout used to be measured from a delivery's ingest
-- created_at, which meant any backlog older than the timeout made isGapTimedOut()
-- unconditionally true (ordering silently disabled during a fan-out burst or Kafka
-- lag spike). It needs to be measured from when the delivery itself first got stuck
-- waiting on a missing predecessor instead. Nullable: only set the first time a
-- delivery is buffered by OrderingBufferService; every other delivery keeps it null.

ALTER TABLE deliveries ADD COLUMN ordering_first_buffered_at TIMESTAMP;

COMMENT ON COLUMN deliveries.ordering_first_buffered_at IS
    'When this delivery was first buffered waiting on a missing predecessor sequence (ordering). Null if never buffered.';

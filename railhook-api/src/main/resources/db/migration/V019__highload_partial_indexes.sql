-- Partial indexes for queue depth metrics (COUNT with status + created_at filter)
-- These make the 30-day windowed COUNT queries index-only scans instead of seq scans.

CREATE INDEX idx_deliveries_pending_created
    ON deliveries(created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_deliveries_processing_created
    ON deliveries(created_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_deliveries_dlq_created
    ON deliveries(created_at)
    WHERE status = 'DLQ';

-- Incoming forward attempts: same pattern
CREATE INDEX idx_incoming_fwd_pending_created
    ON incoming_forward_attempts(created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_incoming_fwd_processing_created
    ON incoming_forward_attempts(created_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_incoming_fwd_dlq_created
    ON incoming_forward_attempts(created_at)
    WHERE status = 'DLQ';

-- Outbox polling: cover status + created_at + kafka_key for window queries
CREATE INDEX idx_outbox_pending_topic_created
    ON outbox_messages(kafka_topic, created_at)
    WHERE status = 'PENDING';

-- Incoming forward retry polling: status + next_retry_at (used by IncomingForwardRetryScheduler)
CREATE INDEX idx_incoming_fwd_retry_query
    ON incoming_forward_attempts(status, next_retry_at)
    WHERE next_retry_at IS NOT NULL;

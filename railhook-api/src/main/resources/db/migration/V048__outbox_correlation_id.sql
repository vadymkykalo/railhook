-- P1: Add correlation_id to outbox_messages for distributed tracing
-- Correlation ID from original HTTP request will be persisted and propagated through Kafka

ALTER TABLE outbox_messages ADD COLUMN correlation_id VARCHAR(128);

-- Index for troubleshooting and correlation-based queries
CREATE INDEX idx_outbox_messages_correlation_id ON outbox_messages(correlation_id) WHERE correlation_id IS NOT NULL;

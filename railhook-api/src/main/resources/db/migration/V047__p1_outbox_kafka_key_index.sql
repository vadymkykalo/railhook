-- Optimize OutboxMessageRepository.findPendingBatchForUpdate window function queries
-- Add composite partial index for kafka_key partitioning in ROW_NUMBER() OVER (PARTITION BY kafka_key ...)
-- Complements existing idx_outbox_messages_project_status (status, project_id, created_at)

CREATE INDEX idx_outbox_messages_kafka_key_status
    ON outbox_messages (status, kafka_key, created_at)
    WHERE status IN ('PENDING', 'FAILED');

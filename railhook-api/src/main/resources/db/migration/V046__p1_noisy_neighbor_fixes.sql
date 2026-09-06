-- Add project_id to outbox_messages for per-project fair scheduling.
-- Nullable because existing rows and some low-volume paths (replay, DLQ retry) may not set it.
ALTER TABLE outbox_messages ADD COLUMN project_id UUID;

-- Index for fair batch claim query partitioning
CREATE INDEX idx_outbox_messages_project_status ON outbox_messages (status, project_id, created_at)
    WHERE status IN ('PENDING', 'FAILED');

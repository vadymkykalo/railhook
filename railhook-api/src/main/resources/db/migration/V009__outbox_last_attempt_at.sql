ALTER TABLE outbox_messages ADD COLUMN last_attempt_at TIMESTAMP WITH TIME ZONE;

-- Backfill: for existing FAILED messages, set last_attempt_at = created_at so backoff works correctly
UPDATE outbox_messages SET last_attempt_at = created_at WHERE status = 'FAILED' AND last_attempt_at IS NULL;

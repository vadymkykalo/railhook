-- Add provider_event_id for incoming webhook dedup
ALTER TABLE incoming_events ADD COLUMN provider_event_id VARCHAR(255);

-- Unique index for dedup: same source + same provider event = duplicate
CREATE UNIQUE INDEX idx_incoming_events_source_provider_event
    ON incoming_events (incoming_source_id, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

-- Add unique index on incoming_forward_attempts to prevent duplicate attempt numbers
CREATE UNIQUE INDEX idx_incoming_forward_attempts_unique_attempt
    ON incoming_forward_attempts (incoming_event_id, destination_id, attempt_number);

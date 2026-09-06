-- =============================================
-- Incoming Webhooks — Highload Optimizations
-- =============================================

-- Composite index for destination lookup by source + enabled (hot path during ingress)
CREATE INDEX idx_incoming_dest_source_enabled
    ON incoming_destinations(incoming_source_id, enabled)
    WHERE enabled = true;

-- Composite index for forward attempt retry polling (hot path in worker scheduler)
-- Covers: WHERE status = 'PENDING' AND next_retry_at <= now ORDER BY next_retry_at ASC
CREATE INDEX idx_incoming_fwd_pending_retry
    ON incoming_forward_attempts(next_retry_at ASC)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;

-- Composite index for max attempt number lookup (hot path during forwarding)
CREATE INDEX idx_incoming_fwd_event_dest_attempt
    ON incoming_forward_attempts(incoming_event_id, destination_id, attempt_number DESC);

-- Partial index for active sources token lookup (hot path during ingress)
CREATE INDEX idx_incoming_sources_active_token
    ON incoming_sources(ingress_path_token)
    WHERE status = 'ACTIVE';

-- Index for project-level event listing with pagination (admin UI)
CREATE INDEX idx_incoming_events_source_id_received
    ON incoming_events(incoming_source_id, received_at DESC);

-- Drop duplicate/less-specific indexes that are covered by new composite ones
DROP INDEX IF EXISTS idx_incoming_fwd_attempts_retry;
DROP INDEX IF EXISTS idx_incoming_fwd_attempts_event_dest;
DROP INDEX IF EXISTS idx_incoming_events_source_received;

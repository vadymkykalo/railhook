-- Durable storage for ordering cursors (last delivered sequence per endpoint)
-- Redis remains as cache layer, this provides fallback after TTL expiry

CREATE TABLE ordering_cursors (
    endpoint_id UUID PRIMARY KEY REFERENCES endpoints(id) ON DELETE CASCADE,
    last_delivered_sequence BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ordering_cursors_updated_at ON ordering_cursors(updated_at);

COMMENT ON TABLE ordering_cursors IS 'Persistent ordering state for FIFO delivery guarantees';
COMMENT ON COLUMN ordering_cursors.last_delivered_sequence IS 'Last successfully delivered sequence number';
COMMENT ON COLUMN ordering_cursors.updated_at IS 'When this cursor was last advanced';

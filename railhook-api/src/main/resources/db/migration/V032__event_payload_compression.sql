-- Event payload compression support
-- Reduces DB storage for large payloads via gzip compression

ALTER TABLE events
    ADD COLUMN payload_compressed BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN events.payload_compressed IS 'Whether the payload column contains gzip-compressed data';

-- Create index for monitoring compression effectiveness
CREATE INDEX idx_events_payload_compressed ON events(payload_compressed) WHERE payload_compressed = true;

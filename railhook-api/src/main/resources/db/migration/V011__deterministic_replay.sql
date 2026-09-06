-- Deterministic Replay: Safe replay, dry-run, idempotency policies
-- Adds idempotency policy per project and idempotency key tracking per delivery.

-- ── Project-level idempotency policy ──
ALTER TABLE projects
    ADD COLUMN idempotency_policy VARCHAR(10) NOT NULL DEFAULT 'NONE';
-- NONE     = no idempotency enforcement (current behavior)
-- AUTO     = platform auto-generates Idempotency-Key header for outgoing webhooks
-- REQUIRED = event ingestion requires idempotency_key, platform sends it in header

COMMENT ON COLUMN projects.idempotency_policy IS 'NONE = no enforcement, AUTO = auto-generate key, REQUIRED = client must provide key';

-- ── Track idempotency key on deliveries for safe replay ──
ALTER TABLE deliveries
    ADD COLUMN idempotency_key VARCHAR(255);

CREATE INDEX idx_deliveries_idempotency_key ON deliveries(idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN deliveries.idempotency_key IS 'Idempotency key sent in outgoing webhook header for deduplication';

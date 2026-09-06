-- =============================================
-- Incoming Webhooks — Enhancements
-- Rate limiting, payload transformation
-- =============================================

-- 1. Rate limiting per source
ALTER TABLE incoming_sources
    ADD COLUMN rate_limit_per_second INTEGER DEFAULT NULL;

-- 2. Payload transformation expression per destination (JSONPath)
ALTER TABLE incoming_destinations
    ADD COLUMN payload_transform TEXT DEFAULT NULL;

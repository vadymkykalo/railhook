-- Add encryption key version tracking to all tables with encrypted fields.
-- Existing rows default to version 1 (the original single-key encryption).
-- This enables zero-downtime encryption key rotation.

ALTER TABLE endpoints
    ADD COLUMN encryption_key_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE incoming_sources
    ADD COLUMN encryption_key_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE incoming_destinations
    ADD COLUMN encryption_key_version INTEGER NOT NULL DEFAULT 1;

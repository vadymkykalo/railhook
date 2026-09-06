-- Add per-plan fanout limit (max deliveries created from a single event).
-- Default 100 matches the existing global config default.
ALTER TABLE plans ADD COLUMN max_fanout_per_event INT NOT NULL DEFAULT 100;

-- Bump enterprise/pro plans to higher limits
UPDATE plans SET max_fanout_per_event = 500 WHERE name = 'enterprise';
UPDATE plans SET max_fanout_per_event = 250 WHERE name = 'pro';

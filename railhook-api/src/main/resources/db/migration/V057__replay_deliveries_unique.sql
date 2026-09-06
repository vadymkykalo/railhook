-- A replay re-delivers an event that already has a delivery row for the same
-- (event, endpoint, subscription). The V045 unique indexes did not account for
-- replay_session_id, so every replay attempt hit
-- idx_deliveries_unique_subscription and the whole batch aborted — replay was
-- unusable for any event that had ever been delivered.
--
-- Split each index in two: the original guarantee still holds for ordinary
-- deliveries (replay_session_id IS NULL), and replayed deliveries get the same
-- guarantee scoped to their own session, so re-running one session stays
-- idempotent while a new session is free to deliver again.

DROP INDEX IF EXISTS idx_deliveries_unique_subscription;
CREATE UNIQUE INDEX idx_deliveries_unique_subscription
    ON deliveries(event_id, endpoint_id, subscription_id)
    WHERE subscription_id IS NOT NULL AND replay_session_id IS NULL;

CREATE UNIQUE INDEX idx_deliveries_unique_subscription_replay
    ON deliveries(event_id, endpoint_id, subscription_id, replay_session_id)
    WHERE subscription_id IS NOT NULL AND replay_session_id IS NOT NULL;

DROP INDEX IF EXISTS idx_deliveries_unique_rule;
CREATE UNIQUE INDEX idx_deliveries_unique_rule
    ON deliveries(event_id, endpoint_id)
    WHERE subscription_id IS NULL AND replay_session_id IS NULL;

CREATE UNIQUE INDEX idx_deliveries_unique_rule_replay
    ON deliveries(event_id, endpoint_id, replay_session_id)
    WHERE subscription_id IS NULL AND replay_session_id IS NOT NULL;

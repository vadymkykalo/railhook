-- Allow replay to create new deliveries for already-delivered events.
-- Old constraint: (event_id, endpoint_id, subscription_id) — blocked replay entirely.
-- New constraint: includes replay_session_id so each replay session can re-deliver.

DROP INDEX IF EXISTS idx_deliveries_unique;

CREATE UNIQUE INDEX idx_deliveries_unique
    ON deliveries(event_id, endpoint_id, subscription_id, COALESCE(replay_session_id, '00000000-0000-0000-0000-000000000000'));

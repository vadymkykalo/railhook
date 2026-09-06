-- A Replay builds a fresh Forward from an Incoming Event already in the store. It used to do
-- that by reading MAX(attempt_number) for the (event, destination) pair and inserting the next
-- one -- computed outside any lock, while the live Retry Ladder was inserting its own successor
-- at exactly that number from inside the finalising transaction. The unique index below made
-- the two collide for real: whichever lost rolled back, and when the loser was the worker its
-- whole finalise went with it, leaving the Attempt PROCESSING until the stuck sweep.
--
-- Same shape of fix as V057 on the Outgoing side: give a Replay its own session and scope the
-- uniqueness to it. Ordinary Forwards (replay_session_id IS NULL) keep the original guarantee;
-- a replayed one gets the same guarantee inside its own session, so re-running a session stays
-- idempotent while a new session is free to forward again. A Replay now starts its ladder at
-- attempt 1 within its session and never reads the live sequence at all.

ALTER TABLE incoming_forward_attempts ADD COLUMN replay_session_id UUID;

DROP INDEX IF EXISTS idx_incoming_forward_attempts_unique_attempt;
CREATE UNIQUE INDEX idx_incoming_forward_attempts_unique_attempt
    ON incoming_forward_attempts (incoming_event_id, destination_id, attempt_number)
    WHERE replay_session_id IS NULL;

CREATE UNIQUE INDEX idx_incoming_forward_attempts_unique_attempt_replay
    ON incoming_forward_attempts (incoming_event_id, destination_id, attempt_number, replay_session_id)
    WHERE replay_session_id IS NOT NULL;

COMMENT ON COLUMN incoming_forward_attempts.replay_session_id IS
    'The Replay that created this Forward, NULL for one created by ingress. Claims are scoped to it, so two Replays of the same event to the same destination cannot claim each other''s attempt rows.';

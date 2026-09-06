-- Fencing token for a forward attempt: the incoming half of what V055 gave deliveries.
--
-- IncomingAttemptStore#finalise guards only on status = PROCESSING. That stops a late
-- writer from overwriting a row that has already reached a terminal state, but it cannot
-- tell "the claim I am finishing" apart from "somebody else's newer claim on the same
-- row" -- which is exactly the case the AttemptStore#finalise contract asks it to detect:
--
--   t0  IncomingForwardRetryScheduler claims the row -> PROCESSING, the POST starts (slow)
--   t1  StuckForwardRecoveryService decides the claim was abandoned -> PENDING
--   t2  the scheduler claims it again -> PROCESSING, and a second worker POSTs.
--       The destination has now received the same webhook twice.
--   t3  the attempt from t0 finally gets a 500. The status guard passes, so it writes
--       FAILED and, being retryable, queues attempt n+1.
--   t4  the attempt from t2 succeeds. It now finds FAILED, refuses to write, and the
--       success is discarded -- while the successor queued at t3 delivers a third copy.
--
-- The outgoing side has fenced on a token since V055; the incoming side carried
-- started_at instead, which cannot work: the CAS that claims a retry sets started_at
-- itself, so the value the claim remembers is stale the moment it is taken.
--
-- Nullable, like deliveries.claim_token: a row that was never claimed carries null, and
-- the stuck-forward sweep clears it when it takes a claim away.

ALTER TABLE incoming_forward_attempts ADD COLUMN claim_token UUID;

COMMENT ON COLUMN incoming_forward_attempts.claim_token IS
    'Fencing token stamped by whichever claim moved this forward attempt to PROCESSING. A '
    'finalizer writes only while it still matches the token its own attempt was claimed '
    'under, so an attempt a stuck sweep has already taken away cannot finalize a row that '
    'has since been reclaimed. Null when unclaimed.';

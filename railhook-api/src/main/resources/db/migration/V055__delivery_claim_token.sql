-- Fencing token for a delivery attempt.
--
-- Finalizing a delivery (markAsSuccess / scheduleRetry / markAsFailed in the worker's
-- WebhookDeliveryService) used to guard only on status = PROCESSING. That is enough to
-- stop a late writer from clobbering an already-terminal row, but it cannot tell "the
-- claim I am finishing" apart from "somebody else's newer claim on the same row":
--
--   t0  RetrySchedulerService claims the row  -> PROCESSING, HTTP attempt starts (slow)
--   t1  StuckDeliveryRecoveryService decides the claim was abandoned -> PENDING
--   t2  RetrySchedulerService claims it again -> PROCESSING (a different attempt)
--   t3  the attempt from t0 finally gets its response and marks the row SUCCESS
--
-- At t3 the status guard passes, because the row is PROCESSING again -- just not for
-- that attempt. The abandoned attempt finalizes a row another worker owns, and the
-- attempt claimed at t2 never reaches the endpoint at all. Reproduced end to end in
-- DeliveryEndToEndIntegrationTest#retryClaimedThenAbandoned_isRecoveredNotStranded.
--
-- Every claim now stamps a fresh token and each finalizer only writes when the token
-- still matches the one its attempt started under. Nullable: rows that were never
-- claimed (freshly ingested, PENDING) carry null, and the stuck-delivery sweep clears
-- it when it takes a claim away.

ALTER TABLE deliveries ADD COLUMN claim_token UUID;

COMMENT ON COLUMN deliveries.claim_token IS
    'Fencing token stamped by whichever claim moved this delivery to PROCESSING. A finalizer '
    'writes only while it still matches the token its own attempt was claimed under, so an '
    'abandoned attempt cannot finalize a row that has since been reclaimed. Null when unclaimed.';

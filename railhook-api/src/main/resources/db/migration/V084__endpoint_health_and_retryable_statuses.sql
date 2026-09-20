-- Two changes that share a migration because they share a seam: both are read by the shared
-- attempt lifecycle, and both are written by whoever configures the target.
--
-- 1. Continuous-failure tracking on the two targets, so a target that has answered nothing but
--    failures for days can be turned off instead of burning its retry budget forever. The
--    circuit breaker already notices a failing target, but it forgets within two minutes —
--    which is what makes it safe to trip, and useless as a record.
--
-- 2. Which HTTP statuses are worth another attempt, per Subscription and per Destination. This
--    was three literals in the worker (408, 429, 5xx); that set is the column default here, so
--    every existing row keeps exactly the behaviour it had.
--
-- Every column is nullable or has a default: a running instance of the previous version writes
-- these tables without them for the length of a rolling deploy.

-- ---------------------------------------------------------------------------
-- 1. Continuous failure, on the Endpoint and on the Destination
-- ---------------------------------------------------------------------------

-- failing_since is the head of the current unbroken run of failures, not the first failure ever:
-- one success clears it. consecutive_failures beside it is what stops a near-idle target being
-- disabled on a single failure that happens to be old — a run has to be both long and busy.
ALTER TABLE endpoints ADD COLUMN failing_since TIMESTAMPTZ;
ALTER TABLE endpoints ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE endpoints ADD COLUMN auto_disabled_at TIMESTAMPTZ;
ALTER TABLE endpoints ADD COLUMN auto_disabled_reason TEXT;

ALTER TABLE incoming_destinations ADD COLUMN failing_since TIMESTAMPTZ;
ALTER TABLE incoming_destinations ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE incoming_destinations ADD COLUMN auto_disabled_at TIMESTAMPTZ;
ALTER TABLE incoming_destinations ADD COLUMN auto_disabled_reason TEXT;

-- What the auto-disable sweep scans: targets currently in a run of failures and still on. Small
-- by construction — a row leaves it the moment the target answers once — and neither table is
-- one that grows with traffic, so a plain CREATE INDEX is not an outage.
CREATE INDEX idx_endpoints_failing_since ON endpoints (failing_since)
    WHERE failing_since IS NOT NULL AND enabled = true AND deleted_at IS NULL;

CREATE INDEX idx_incoming_destinations_failing_since ON incoming_destinations (failing_since)
    WHERE failing_since IS NOT NULL AND enabled = true;

COMMENT ON COLUMN endpoints.failing_since IS 'Start of the current unbroken run of failed attempts; null when the last attempt succeeded or none has been made.';
COMMENT ON COLUMN endpoints.consecutive_failures IS 'Attempts in the current unbroken run of failures; reset to 0 by any success.';
COMMENT ON COLUMN endpoints.auto_disabled_at IS 'When Railhook turned this endpoint off for continuous failure; null when its owner turned it off, or when it is on.';
COMMENT ON COLUMN endpoints.auto_disabled_reason IS 'Why Railhook turned it off, in words meant for its owner.';

COMMENT ON COLUMN incoming_destinations.failing_since IS 'Start of the current unbroken run of failed attempts; null when the last attempt succeeded or none has been made.';
COMMENT ON COLUMN incoming_destinations.consecutive_failures IS 'Attempts in the current unbroken run of failures; reset to 0 by any success.';
COMMENT ON COLUMN incoming_destinations.auto_disabled_at IS 'When Railhook turned this destination off for continuous failure; null when its owner turned it off, or when it is on.';
COMMENT ON COLUMN incoming_destinations.auto_disabled_reason IS 'Why Railhook turned it off, in words meant for its owner.';

-- ---------------------------------------------------------------------------
-- 2. Which statuses are worth another attempt
-- ---------------------------------------------------------------------------

-- The default is the set the worker used to hardcode, so nothing changes for a row that does not
-- say otherwise. It is written down twice by necessity — here and in RetryableStatuses.DEFAULT_SPEC
-- — because SQL cannot reference a Java constant; SchemaRetryLadderDefaultsTest keeps the two
-- saying the same thing.
ALTER TABLE subscriptions ADD COLUMN retryable_statuses TEXT NOT NULL DEFAULT '408,429,500-599';
ALTER TABLE incoming_destinations ADD COLUMN retryable_statuses TEXT NOT NULL DEFAULT '408,429,500-599';

-- Copied onto the delivery at creation, as the ladder already is: OutgoingAttemptStore never
-- loads the Subscription, and a subscription edited mid-ladder must not change the rules an
-- obligation already in flight is being judged by.
ALTER TABLE deliveries ADD COLUMN retryable_statuses TEXT NOT NULL DEFAULT '408,429,500-599';

COMMENT ON COLUMN subscriptions.retryable_statuses IS 'Which HTTP statuses are worth another attempt, e.g. 408,429,500-599 or >=500,!501.';
COMMENT ON COLUMN incoming_destinations.retryable_statuses IS 'Which HTTP statuses are worth another attempt, e.g. 408,429,500-599 or >=500,!501.';
COMMENT ON COLUMN deliveries.retryable_statuses IS 'Copied from the subscription when the delivery was created, so an edit mid-ladder does not change the rules in flight.';

-- ---------------------------------------------------------------------------
-- 3. An alert nobody wrote a rule for
-- ---------------------------------------------------------------------------

-- Every alert event so far hangs off a rule its owner created. An auto-disable is Railhook's own
-- decision about a target, so there is no rule to hang it on, and inventing a hidden rule per
-- organization would put a row in the alert-rules list that nobody can explain or delete.
ALTER TABLE alert_events ALTER COLUMN alert_rule_id DROP NOT NULL;

-- Which target the event is about, when it is about one. Alert rules already carry endpoint_id;
-- events did not, because they inherited it from their rule.
ALTER TABLE alert_events ADD COLUMN endpoint_id UUID;

COMMENT ON COLUMN alert_events.alert_rule_id IS 'The rule that fired, or null for an event Railhook raised on its own (an auto-disabled endpoint).';
COMMENT ON COLUMN alert_events.endpoint_id IS 'The endpoint this event is about, when it is about one.';

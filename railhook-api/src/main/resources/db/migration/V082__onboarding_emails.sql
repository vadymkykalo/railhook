-- The two onboarding mails a new account gets when a deployment turns them on
-- (ONBOARDING_EMAILS_ENABLED): a welcome when its address is proven, and one nudge two days
-- later if its organization has sent and received nothing. Each is sent at most once, and these
-- are the record of that — a timestamp rather than a flag, so the nudge can be timed from the
-- welcome.
--
-- Both nullable: a running instance of the previous version inserts users without them.

ALTER TABLE users ADD COLUMN onboarding_welcome_sent_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN onboarding_nudge_sent_at TIMESTAMPTZ;

-- Every account that exists before this migration is past onboarding, or was never going to get
-- it: it is marked as having had both, so turning the setting on never mails a years-old account
-- a welcome or asks it whether it is stuck.
UPDATE users SET onboarding_welcome_sent_at = now(), onboarding_nudge_sent_at = now();

-- What the hourly nudge job scans: accounts welcomed and not yet nudged. Small by construction —
-- a row leaves it within a few days of entering — and users is not a table that grows fast.
CREATE INDEX idx_users_onboarding_nudge_due ON users (onboarding_welcome_sent_at)
    WHERE onboarding_nudge_sent_at IS NULL;

COMMENT ON COLUMN users.onboarding_welcome_sent_at IS 'When the onboarding welcome mail was sent; null until the address is verified with onboarding mail on.';
COMMENT ON COLUMN users.onboarding_nudge_sent_at IS 'When the day-2 onboarding nudge was sent; set at most once.';

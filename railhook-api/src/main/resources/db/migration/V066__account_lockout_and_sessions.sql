-- Three gaps in password authentication, all of them in the same table's neighbourhood.
--
-- 1. LOCKOUT. Guessing a password was bounded only by AuthRateLimiterService: 10 attempts a
--    minute per IP and per email, which is 14,400 a day against one account. Worse, that
--    limiter falls back to a per-instance in-memory bucket when Redis is unavailable, so the
--    real ceiling was multiplied by the replica count. Consecutive-failure state therefore
--    lives in Postgres, which every replica shares and which does not have a degraded mode.
--
--    The counter is deliberately on `users` rather than in its own table: it is one row per
--    account with no history worth keeping, and putting it beside the password hash means the
--    read that fetches the hash already has it.
--
-- 2. SESSIONS. Refresh tokens were self-contained JWTs, so nothing anywhere knew how many were
--    outstanding. A user could not see that a laptop they no longer own is still signed in, and
--    could not see a CLI device-code grant at all -- the one credential most likely to outlive
--    the machine it was issued to. Each row is one refresh-token family: created at login, its
--    jti rotated on every refresh, and gone when it is revoked or expires.
--
-- 3. THE ORGANIZATION A SESSION IS LOOKING AT. Login picked the oldest membership and refresh
--    picked it again, so a second organization was unreachable no matter what the UI did.
--    Holding it on the session makes switching an UPDATE of one row that invalidates no token
--    -- see user_sessions.organization_id below for why this column is not tenant-scoped.
--
-- 4. API KEY ROTATION. Endpoint signing secrets have rotated with a grace window since V001's
--    columns were finally written to; API keys had create and revoke and nothing in between, so
--    rolling one over was a create-then-revoke race the customer had to orchestrate by hand.

-- ---------------------------------------------------------------------------
-- 1. Account lockout
-- ---------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_failed_login_at TIMESTAMPTZ,
    ADD COLUMN lockout_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN users.failed_login_attempts IS
    'Consecutive failed logins. Reset to zero by a successful login, a password change and a '
    'password reset -- anything that proves the account holder is present.';
COMMENT ON COLUMN users.lockout_expires_at IS
    'When the current lockout lapses. Always set: a lockout that needed an administrator to '
    'lift it would turn a nuisance into an outage, and would make locking a known address a '
    'denial of service with no self-service way out.';

-- ---------------------------------------------------------------------------
-- 2 & 3. Sessions
-- ---------------------------------------------------------------------------

CREATE TABLE user_sessions (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    refresh_token_jti VARCHAR(64) NOT NULL UNIQUE,
    client            VARCHAR(16) NOT NULL,
    user_agent        VARCHAR(512),
    ip_address        VARCHAR(45),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ
);

COMMENT ON TABLE user_sessions IS
    'One row per live refresh-token family. The durable half of session management: the JWT '
    'blacklist in Redis is what makes a revocation take effect, this is what makes the list of '
    'what could be revoked survive a Redis flush.';

COMMENT ON COLUMN user_sessions.organization_id IS
    'The organization this session is currently scoped to, which the organization switcher '
    'changes and refresh reads back. Deliberately NOT a @TenantId column: a session belongs to '
    'a person, not to a tenant, and scoping this table by it would hide a user''s own sessions '
    'from them the moment they switched -- including the "sign out everywhere" that is supposed '
    'to reach the session they are trying to get rid of.';

COMMENT ON COLUMN user_sessions.refresh_token_jti IS
    'The jti of the refresh token this session currently accepts. Rotated on every refresh, so '
    'a token whose jti is no longer here has been superseded. UNIQUE, which is also what makes '
    'the refresh path''s lookup a single index probe.';

COMMENT ON COLUMN user_sessions.client IS
    'WEB for a browser sign-in, CLI for a device-code grant. A CLI grant is the one people are '
    'most surprised to still have, so it is worth naming separately in the list.';

CREATE INDEX idx_user_sessions_user_active
    ON user_sessions (user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL;

-- Purging expired rows is a full-table sweep by time, so it wants its own index rather than
-- riding the partial one above, which excludes nothing that has merely expired.
CREATE INDEX idx_user_sessions_expires_at ON user_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- 4. API key rotation
-- ---------------------------------------------------------------------------

ALTER TABLE api_keys
    ADD COLUMN rotated_at     TIMESTAMPTZ,
    ADD COLUMN replaced_by_id UUID REFERENCES api_keys(id) ON DELETE SET NULL;

COMMENT ON COLUMN api_keys.rotated_at IS
    'Set on the outgoing key when it is rotated. What distinguishes "expires_at is set because '
    'this key is being retired" from "expires_at is set because someone asked for a key that '
    'expires", which the UI has to say differently.';
COMMENT ON COLUMN api_keys.replaced_by_id IS
    'The key that took over. ON DELETE SET NULL rather than CASCADE: deleting the successor '
    'must never take the audit trail of its predecessor with it.';

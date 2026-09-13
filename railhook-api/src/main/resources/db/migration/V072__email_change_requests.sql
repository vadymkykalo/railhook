-- Changing the address an account signs in with.
--
-- email_change_requests holds a change from the moment it is asked for until it is confirmed,
-- cancelled or has lapsed. A verified account keeps its address until the new one is proved:
-- the confirmation token goes to the new address and the cancel token ("this wasn't me") to the
-- old one. Only the SHA-256 of either token is stored, as with verification and reset tokens.
-- An unverified account's change applies at once (it proved nothing about the old address
-- either) and is recorded as APPLIED, so every change counts towards the daily cap.
--
-- verification_email_sends records each mail that asks an address to prove itself. Both caps
-- live in Postgres rather than in the Redis rate limiter on purpose: they are a day long, and
-- a Redis restart must not hand every account a fresh allowance of mail to a stranger's inbox.
CREATE TABLE email_change_requests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    previous_email    VARCHAR(255) NOT NULL,
    new_email         VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    token_hash        VARCHAR(64),
    cancel_token_hash VARCHAR(64),
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    CONSTRAINT ck_email_change_requests_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'APPLIED')),
    CONSTRAINT uq_email_change_requests_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_email_change_requests_cancel_token_hash UNIQUE (cancel_token_hash)
);

CREATE INDEX idx_email_change_requests_user_created ON email_change_requests (user_id, created_at);

-- At most one change waits for confirmation per account; asking again replaces it.
CREATE UNIQUE INDEX uq_email_change_requests_one_pending
    ON email_change_requests (user_id) WHERE status = 'PENDING';

COMMENT ON TABLE email_change_requests IS
    'Requested changes of a user''s sign-in address; only SHA-256 hashes of the confirm and cancel tokens are stored';

CREATE TABLE verification_email_sends (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason     VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_verification_email_sends_user_created ON verification_email_sends (user_id, created_at);

COMMENT ON TABLE verification_email_sends IS
    'One row per mail asking an address to prove itself, counted for the per-account daily cap';

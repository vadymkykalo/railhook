-- Signing in with Google.
--
-- user_identities links an account to the identity a provider vouches for. It is keyed on the
-- provider's subject, not the address: a person can change the address on their Google account
-- and must still land in the same Railhook account, and an address that later belongs to someone
-- else must not.
--
-- sign_in_handoffs carries a finished sign-in from the API's callback to the dashboard. The
-- callback is a browser redirect, and a token in a URL ends up in history, logs and Referer
-- headers — so the redirect carries a random code instead, the row holds only its hash, and the
-- dashboard trades it once, within a minute, for the same session a password sign-in returns.
--
-- password_hash becomes nullable because an account created through Google has no password until
-- its owner sets one with "Forgot password". Relaxing a constraint is safe for instances still
-- running the previous release: they always write a hash.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identities (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider   VARCHAR(32)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject)
);

CREATE INDEX idx_user_identities_user_id ON user_identities (user_id);

COMMENT ON TABLE user_identities IS
    'An identity provider account (e.g. Google) linked to a user, matched on the provider''s permanent subject';

CREATE TABLE sign_in_handoffs (
    code_hash       VARCHAR(64) PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_created BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sign_in_handoffs_expires_at ON sign_in_handoffs (expires_at);

COMMENT ON TABLE sign_in_handoffs IS
    'One-time codes that hand a completed provider sign-in to the dashboard; only the SHA-256 of the code is stored';

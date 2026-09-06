-- Hash invite tokens for security (prevent plaintext leak on DB compromise)
-- Existing plaintext tokens are hashed in-place using SHA-256 + Base64 encoding.
-- The column is renamed to make the semantic change explicit.

ALTER TABLE memberships RENAME COLUMN invite_token TO invite_token_hash;

-- Drop and recreate the unique index with the new column name
DROP INDEX IF EXISTS idx_memberships_invite_token;
CREATE UNIQUE INDEX idx_memberships_invite_token_hash ON memberships(invite_token_hash) WHERE invite_token_hash IS NOT NULL;

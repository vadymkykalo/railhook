-- password_reset_token and verification_token now store
-- CryptoUtils.hashApiKey(token) (SHA-256 + Base64) instead of the raw token,
-- matching the pattern already used for invite tokens (see V034).
--
-- Existing plaintext tokens in these columns cannot be turned into a useful
-- hash retroactively -- we would have to hash the already-persisted value,
-- which is not the same as hashing the original token the user was emailed,
-- so the stored value would never match a lookup again anyway. Rather than
-- leave dead, unusable-but-present values behind, invalidate them outright:
-- any pending "reset your password" or "verify your email" request issued
-- before this migration is discarded and the user must re-request it.
UPDATE users
SET password_reset_token = NULL,
    password_reset_token_expires_at = NULL
WHERE password_reset_token IS NOT NULL;

UPDATE users
SET verification_token = NULL,
    verification_token_expires_at = NULL
WHERE verification_token IS NOT NULL;

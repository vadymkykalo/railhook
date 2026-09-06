-- Add invite token support for membership hardening
ALTER TABLE memberships ADD COLUMN invite_token VARCHAR(64);
ALTER TABLE memberships ADD COLUMN invite_expires_at TIMESTAMP;
ALTER TABLE memberships ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE';

CREATE UNIQUE INDEX idx_memberships_invite_token ON memberships(invite_token) WHERE invite_token IS NOT NULL;

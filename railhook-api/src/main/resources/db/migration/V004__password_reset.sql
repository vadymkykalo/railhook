ALTER TABLE users
    ADD COLUMN password_reset_token VARCHAR(64),
    ADD COLUMN password_reset_token_expires_at TIMESTAMP;

CREATE INDEX idx_users_password_reset_token ON users(password_reset_token);

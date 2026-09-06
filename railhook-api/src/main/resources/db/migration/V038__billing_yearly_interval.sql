-- ============================================================
-- Add yearly billing support
-- ============================================================

-- Add yearly price to plans
ALTER TABLE plans ADD COLUMN price_yearly_cents INT NOT NULL DEFAULT 0;

-- Set yearly prices (roughly 2 months free = 10 * monthly)
UPDATE plans SET price_yearly_cents = 29000  WHERE name = 'starter';
UPDATE plans SET price_yearly_cents = 99000  WHERE name = 'pro';
UPDATE plans SET price_yearly_cents = -1     WHERE name = 'enterprise';

-- Add billing interval to subscriptions
ALTER TABLE billing_subscriptions ADD COLUMN billing_interval VARCHAR(20) NOT NULL DEFAULT 'MONTHLY';

CREATE INDEX idx_billing_subscriptions_interval ON billing_subscriptions(billing_interval);

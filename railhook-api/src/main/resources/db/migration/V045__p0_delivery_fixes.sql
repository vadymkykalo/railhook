-- Allow NULL subscription_id for rule-driven deliveries.
-- Rule ROUTE actions create deliveries without a subscription.
ALTER TABLE deliveries ALTER COLUMN subscription_id DROP NOT NULL;

-- Add delivery_origin to distinguish subscription-based vs rule-based deliveries
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS delivery_origin VARCHAR(20) NOT NULL DEFAULT 'SUBSCRIPTION';

-- Drop old unique index that requires subscription_id (breaks with NULLs)
DROP INDEX IF EXISTS idx_deliveries_unique;
-- New unique index: for subscription deliveries use (event_id, endpoint_id, subscription_id),
-- for rule deliveries use (event_id, endpoint_id) with NULL subscription_id partial index
CREATE UNIQUE INDEX idx_deliveries_unique_subscription
    ON deliveries(event_id, endpoint_id, subscription_id)
    WHERE subscription_id IS NOT NULL;
CREATE UNIQUE INDEX idx_deliveries_unique_rule
    ON deliveries(event_id, endpoint_id)
    WHERE subscription_id IS NULL;

-- Partial index for rule-based deliveries
CREATE INDEX IF NOT EXISTS idx_deliveries_rule_origin
    ON deliveries(endpoint_id, created_at DESC)
    WHERE delivery_origin = 'RULE';

-- Add UNIQUE index on delivery_attempts(delivery_id, attempt_number).
-- For incoming_forward_attempts this was already done in V016; delivery_attempts was missing.
DROP INDEX IF EXISTS idx_delivery_attempts_delivery_attempt_number;
CREATE UNIQUE INDEX idx_delivery_attempts_unique_attempt
    ON delivery_attempts(delivery_id, attempt_number);

-- ============================================================
-- Plan Catalog + Organization billing fields
-- ============================================================

CREATE TABLE plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(50)  NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,

    -- Quotas (-1 = unlimited)
    max_events_per_month      BIGINT  NOT NULL DEFAULT 10000,
    max_endpoints_per_project INT     NOT NULL DEFAULT 5,
    max_projects              INT     NOT NULL DEFAULT 3,
    max_members               INT     NOT NULL DEFAULT 5,
    rate_limit_per_second     INT     NOT NULL DEFAULT 10,
    max_retention_days        INT     NOT NULL DEFAULT 7,

    -- Feature flags stored as JSONB for flexibility
    features        JSONB   NOT NULL DEFAULT '{}',

    -- Pricing (cents; -1 = custom / contact-sales)
    price_monthly_cents INT NOT NULL DEFAULT 0,

    is_active       BOOLEAN   NOT NULL DEFAULT true,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed default plans
INSERT INTO plans (name, display_name, max_events_per_month, max_endpoints_per_project,
    max_projects, max_members, rate_limit_per_second, max_retention_days,
    price_monthly_cents, features)
VALUES
    ('free',       'Free',       10000,    5,   3,   5,   10,  7,    0,
     '{"workflows": false, "rules": false, "replay": false, "mTLS": false, "sso": false}'),
    ('starter',    'Starter',    100000,   20,  10,  10,  50,  30,   2900,
     '{"workflows": true, "rules": true, "replay": true, "mTLS": false, "sso": false}'),
    ('pro',        'Pro',        1000000,  100, 50,  50,  200, 90,   9900,
     '{"workflows": true, "rules": true, "replay": true, "mTLS": true, "sso": false}'),
    ('enterprise', 'Enterprise', -1,       -1,  -1,  -1,  1000, 365, -1,
     '{"workflows": true, "rules": true, "replay": true, "mTLS": true, "sso": true}');

-- Self-hosted plan: unlimited everything, never shown in SaaS UI
INSERT INTO plans (name, display_name, max_events_per_month, max_endpoints_per_project,
    max_projects, max_members, rate_limit_per_second, max_retention_days,
    price_monthly_cents, features, is_active)
VALUES
    ('self_hosted', 'Self-Hosted', -1, -1, -1, -1, 10000, -1, 0,
     '{"workflows": true, "rules": true, "replay": true, "mTLS": true, "sso": true}',
     true);

-- Add billing columns to organizations
ALTER TABLE organizations ADD COLUMN plan_id UUID REFERENCES plans(id);
ALTER TABLE organizations ADD COLUMN external_customer_id VARCHAR(255);
ALTER TABLE organizations ADD COLUMN billing_email VARCHAR(255);
ALTER TABLE organizations ADD COLUMN billing_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE';

-- All existing orgs get 'free' plan
UPDATE organizations SET plan_id = (SELECT id FROM plans WHERE name = 'free');
ALTER TABLE organizations ALTER COLUMN plan_id SET NOT NULL;

-- Index for billing queries
CREATE INDEX idx_organizations_plan_id ON organizations(plan_id);
CREATE INDEX idx_organizations_external_customer_id ON organizations(external_customer_id) WHERE external_customer_id IS NOT NULL;

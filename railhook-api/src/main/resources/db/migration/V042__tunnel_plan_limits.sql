-- Add tunnel limits to plans
ALTER TABLE plans ADD COLUMN max_active_tunnels INT NOT NULL DEFAULT 0;

-- Free: tunnels disabled (0), feature flag false (already set in V036)
-- Starter: 3 active tunnels
UPDATE plans SET max_active_tunnels = 0  WHERE name = 'free';
UPDATE plans SET max_active_tunnels = 3  WHERE name = 'starter';
UPDATE plans SET max_active_tunnels = 10 WHERE name = 'pro';
UPDATE plans SET max_active_tunnels = -1 WHERE name = 'enterprise';
UPDATE plans SET max_active_tunnels = -1 WHERE name = 'self_hosted';

-- Composite index for quota enforcement: countByOrganizationIdAndStatus(orgId, ACTIVE)
CREATE INDEX idx_tunnel_sessions_org_status ON tunnel_sessions (organization_id, status);

-- Add tunnels feature flag to plan features
UPDATE plans SET features = features || '{"tunnels": false}'::jsonb WHERE name = 'free';
UPDATE plans SET features = features || '{"tunnels": true}'::jsonb  WHERE name = 'starter';
UPDATE plans SET features = features || '{"tunnels": true}'::jsonb  WHERE name = 'pro';
UPDATE plans SET features = features || '{"tunnels": true}'::jsonb  WHERE name = 'enterprise';
UPDATE plans SET features = features || '{"tunnels": true}'::jsonb  WHERE name = 'self_hosted';

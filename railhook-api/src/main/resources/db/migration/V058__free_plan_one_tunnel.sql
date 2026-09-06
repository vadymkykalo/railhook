-- Give the free plan one active tunnel.
--
-- V042 seeded free with max_active_tunnels = 0 and features.tunnels = false, so
-- the CLI tunnel — the one feature a developer can try in their first session,
-- before any endpoint of theirs is reachable from the internet — was the one
-- feature a free account could not touch. The landing page sells it; a signup
-- that hits QuotaExceededException on the first thing they try is a worse
-- outcome than not having shipped the feature.
--
-- One, not three: concurrency is what makes a tunnel worth paying for, and a
-- single session is enough to point a live webhook at localhost. Free is still
-- bounded elsewhere — 10 requests a second and 10,000 events a month.
--
-- Both halves matter: EntitlementService.checkTunnelLimit() rejects on the
-- feature flag before it ever reads the count, so raising max_active_tunnels
-- alone would change nothing.
UPDATE plans
SET max_active_tunnels = 1,
    features = features || '{"tunnels": true}'::jsonb
WHERE name = 'free';

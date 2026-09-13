-- Give the free plan every feature.
--
-- Free was seeded with workflows, rules, replay and mTLS off, the upgrade path being Starter or
-- Pro. The hosted cloud now runs billing with no payment provider, so nothing can be bought:
-- a free organization that opened Workflows filled in the form and got "Feature 'workflows' is
-- not available on your current plan. Please upgrade" — an instruction with no way to follow it.
--
-- Until paid plans are on sale, free is bounded by its quotas — 10,000 events a month, 3
-- projects, 5 endpoints per project, 7 days of retention, 10 requests a second, one tunnel —
-- and not by features. Merged into the existing object so no other key is touched.
UPDATE plans
SET features = features || '{"workflows": true, "rules": true, "replay": true, "mTLS": true, "tunnels": true}'::jsonb
WHERE name = 'free';

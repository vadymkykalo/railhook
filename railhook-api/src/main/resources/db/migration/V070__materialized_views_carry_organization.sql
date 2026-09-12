-- Put organization_id in the dashboard views, so the queries over them can be confined the way
-- every other query in this codebase is.
--
-- MaterializedViewRepository is raw JdbcTemplate. There is no Hibernate session behind it, so
-- @TenantId never applies, and the views carried only project_id — meaning the SQL had no
-- organization to filter on even if it had wanted to. It was safe only because both callers
-- happen to load the Project under tenant scope first and pass its id down. That is a
-- convention held in two call sites, and NativeQueryTenantPredicateTest could not see it:
-- the ratchet scans for @Query(nativeQuery = true), and this class has none.
--
-- DROP and CREATE rather than ALTER: a materialized view's columns come from its query.
-- Rebuilding is not free but these are 30-day rollups over indexed tables, and this runs once.
-- The unique indexes are what REFRESH ... CONCURRENTLY requires, so they come back with them.
DROP MATERIALIZED VIEW IF EXISTS mv_delivery_stats;
DROP MATERIALIZED VIEW IF EXISTS mv_incoming_stats;

CREATE MATERIALIZED VIEW mv_delivery_stats AS
SELECT
    e.organization_id,
    e.project_id,
    d.status::text,
    COUNT(*) as cnt,
    DATE_TRUNC('day', d.created_at) as day
FROM deliveries d
JOIN events e ON d.event_id = e.id
WHERE d.created_at > NOW() - INTERVAL '30 days'
GROUP BY e.organization_id, e.project_id, d.status, DATE_TRUNC('day', d.created_at);

CREATE UNIQUE INDEX idx_mv_delivery_stats ON mv_delivery_stats(project_id, status, day);
CREATE INDEX idx_mv_delivery_stats_day ON mv_delivery_stats(day DESC);
CREATE INDEX idx_mv_delivery_stats_org ON mv_delivery_stats(organization_id, project_id);

CREATE MATERIALIZED VIEW mv_incoming_stats AS
SELECT
    s.organization_id,
    s.project_id,
    COUNT(e.id) as event_count,
    DATE_TRUNC('day', e.received_at) as day
FROM incoming_events e
JOIN incoming_sources s ON e.incoming_source_id = s.id
WHERE e.received_at > NOW() - INTERVAL '30 days'
GROUP BY s.organization_id, s.project_id, DATE_TRUNC('day', e.received_at);

CREATE UNIQUE INDEX idx_mv_incoming_stats ON mv_incoming_stats(project_id, day);
CREATE INDEX idx_mv_incoming_stats_day ON mv_incoming_stats(day DESC);
CREATE INDEX idx_mv_incoming_stats_org ON mv_incoming_stats(organization_id, project_id);

COMMENT ON MATERIALIZED VIEW mv_delivery_stats IS
    'Dashboard rollup. organization_id is here so MaterializedViewRepository can filter on it: that class is raw JdbcTemplate, so @TenantId does not reach it and the predicate has to be written by hand.';

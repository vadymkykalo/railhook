-- Materialized view for dashboard delivery stats (reduces runtime GROUP BY load)

CREATE MATERIALIZED VIEW mv_delivery_stats AS
SELECT 
    e.project_id,
    d.status::text,
    COUNT(*) as cnt,
    DATE_TRUNC('day', d.created_at) as day
FROM deliveries d
JOIN events e ON d.event_id = e.id
WHERE d.created_at > NOW() - INTERVAL '30 days'
GROUP BY e.project_id, d.status, DATE_TRUNC('day', d.created_at);

CREATE UNIQUE INDEX idx_mv_delivery_stats ON mv_delivery_stats(project_id, status, day);
CREATE INDEX idx_mv_delivery_stats_day ON mv_delivery_stats(day DESC);

-- Materialized view for incoming events stats
CREATE MATERIALIZED VIEW mv_incoming_stats AS
SELECT 
    s.project_id,
    COUNT(e.id) as event_count,
    DATE_TRUNC('day', e.received_at) as day
FROM incoming_events e
JOIN incoming_sources s ON e.incoming_source_id = s.id
WHERE e.received_at > NOW() - INTERVAL '30 days'
GROUP BY s.project_id, DATE_TRUNC('day', e.received_at);

CREATE UNIQUE INDEX idx_mv_incoming_stats ON mv_incoming_stats(project_id, day);
CREATE INDEX idx_mv_incoming_stats_day ON mv_incoming_stats(day DESC);

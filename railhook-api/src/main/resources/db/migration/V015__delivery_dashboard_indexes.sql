-- Composite index for time-bounded dashboard queries (delivery stats by project + time range)
CREATE INDEX idx_deliveries_created_at ON deliveries(created_at);

-- Composite index for endpoint health dashboard query (endpoint + time range + status)
CREATE INDEX idx_deliveries_endpoint_created_status ON deliveries(endpoint_id, created_at, status);

-- Alert rules: configurable per-project alert conditions
CREATE TABLE alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    alert_type VARCHAR(50) NOT NULL,       -- FAILURE_RATE, DLQ_THRESHOLD, CONSECUTIVE_FAILURES, LATENCY_THRESHOLD
    severity VARCHAR(20) NOT NULL DEFAULT 'WARNING', -- INFO, WARNING, CRITICAL
    channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP',    -- IN_APP, EMAIL, WEBHOOK
    threshold_value DOUBLE PRECISION NOT NULL,
    window_minutes INT NOT NULL DEFAULT 5,
    endpoint_id UUID REFERENCES endpoints(id) ON DELETE CASCADE, -- NULL = all endpoints
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_rules_project ON alert_rules(project_id);
CREATE INDEX idx_alert_rules_project_enabled ON alert_rules(project_id, enabled) WHERE enabled = TRUE;

-- Alert events: fired alert history
CREATE TABLE alert_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_rule_id UUID NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    severity VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    message TEXT,
    current_value DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_events_project ON alert_events(project_id, created_at DESC);
CREATE INDEX idx_alert_events_rule ON alert_events(alert_rule_id, created_at DESC);
CREATE INDEX idx_alert_events_unresolved ON alert_events(project_id, resolved) WHERE resolved = FALSE;

-- Usage snapshots: daily aggregated usage per project (populated by scheduled job)
CREATE TABLE usage_daily (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    events_count BIGINT NOT NULL DEFAULT 0,
    deliveries_count BIGINT NOT NULL DEFAULT 0,
    successful_deliveries BIGINT NOT NULL DEFAULT 0,
    failed_deliveries BIGINT NOT NULL DEFAULT 0,
    dlq_count BIGINT NOT NULL DEFAULT 0,
    incoming_events_count BIGINT NOT NULL DEFAULT 0,
    incoming_forwards_count BIGINT NOT NULL DEFAULT 0,
    avg_latency_ms DOUBLE PRECISION,
    p95_latency_ms DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, date)
);

CREATE INDEX idx_usage_daily_project_date ON usage_daily(project_id, date DESC);

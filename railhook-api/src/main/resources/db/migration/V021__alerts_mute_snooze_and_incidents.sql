-- Add mute/snooze support to alert rules
ALTER TABLE alert_rules ADD COLUMN muted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE alert_rules ADD COLUMN snoozed_until TIMESTAMPTZ;
ALTER TABLE alert_rules ADD COLUMN webhook_url VARCHAR(2048);
ALTER TABLE alert_rules ADD COLUMN email_recipients TEXT; -- comma-separated

-- Incidents: group related failures for RCA
CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN, INVESTIGATING, RESOLVED
    severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    rca_notes TEXT,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incidents_project ON incidents(project_id, created_at DESC);
CREATE INDEX idx_incidents_open ON incidents(project_id, status) WHERE status != 'RESOLVED';

-- Incident timeline entries
CREATE TABLE incident_timeline (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    entry_type VARCHAR(30) NOT NULL, -- FAILURE, RETRY, REPLAY, NOTE, STATUS_CHANGE
    title VARCHAR(500) NOT NULL,
    detail TEXT,
    delivery_id UUID REFERENCES deliveries(id) ON DELETE SET NULL,
    endpoint_id UUID REFERENCES endpoints(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incident_timeline ON incident_timeline(incident_id, created_at);

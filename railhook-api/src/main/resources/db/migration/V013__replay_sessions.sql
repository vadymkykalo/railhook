-- Event Time Machine: replay sessions for bulk time-range replay
CREATE TABLE replay_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id),
    created_by      UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    -- Filter criteria (immutable after creation)
    from_date       TIMESTAMPTZ NOT NULL,
    to_date         TIMESTAMPTZ NOT NULL,
    event_type      VARCHAR(255),
    endpoint_id     UUID REFERENCES endpoints(id),
    source_status   VARCHAR(20),
    
    -- Progress tracking
    total_events    INTEGER NOT NULL DEFAULT 0,
    processed_events INTEGER NOT NULL DEFAULT 0,
    deliveries_created INTEGER NOT NULL DEFAULT 0,
    errors          INTEGER NOT NULL DEFAULT 0,
    last_processed_event_id UUID,
    error_message   TEXT,
    
    -- Timing
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    -- Optimistic locking
    version         INTEGER NOT NULL DEFAULT 0,
    
    CONSTRAINT chk_replay_status CHECK (status IN ('PENDING', 'ESTIMATING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELLING')),
    CONSTRAINT chk_replay_dates CHECK (from_date < to_date)
);

CREATE INDEX idx_replay_sessions_project_id ON replay_sessions(project_id);
CREATE INDEX idx_replay_sessions_status ON replay_sessions(status);
CREATE INDEX idx_replay_sessions_project_status ON replay_sessions(project_id, status);
CREATE INDEX idx_replay_sessions_created_at ON replay_sessions(created_at DESC);

-- Stamp replay_session_id on deliveries to track which deliveries were created by replay
ALTER TABLE deliveries ADD COLUMN replay_session_id UUID REFERENCES replay_sessions(id);
CREATE INDEX idx_deliveries_replay_session_id ON deliveries(replay_session_id) WHERE replay_session_id IS NOT NULL;

-- Cursor-based pagination index for event scanning (highload)
CREATE INDEX idx_events_project_created_id ON events(project_id, created_at, id);
CREATE INDEX idx_events_project_type_created_id ON events(project_id, event_type, created_at, id);

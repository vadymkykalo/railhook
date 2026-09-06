-- =============================================
-- Workflow Automation Engine
-- =============================================

-- 1. Workflows (stores the visual DAG definition)
CREATE TABLE workflows (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT false,
    -- Full React Flow graph: {nodes: [...], edges: [...]}
    definition      JSONB NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
    -- Trigger config for fast lookup
    trigger_type    VARCHAR(50) NOT NULL DEFAULT 'WEBHOOK_EVENT',
    trigger_config  JSONB NOT NULL DEFAULT '{}',
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, name)
);

CREATE INDEX idx_workflows_project ON workflows(project_id);
CREATE INDEX idx_workflows_enabled ON workflows(project_id, enabled) WHERE enabled = true;
CREATE INDEX idx_workflows_trigger ON workflows(trigger_type, enabled) WHERE enabled = true;

-- 2. Workflow Executions (one per trigger firing)
CREATE TABLE workflow_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id     UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    trigger_event_id UUID,
    status          VARCHAR(50) NOT NULL DEFAULT 'RUNNING',
    trigger_data    JSONB,
    started_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    error_message   TEXT,
    duration_ms     INTEGER
);

CREATE INDEX idx_wf_exec_workflow ON workflow_executions(workflow_id);
CREATE INDEX idx_wf_exec_status ON workflow_executions(status);
CREATE INDEX idx_wf_exec_started ON workflow_executions(started_at DESC);

-- 3. Step Executions (one per node executed)
CREATE TABLE workflow_step_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id    UUID NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    node_id         VARCHAR(100) NOT NULL,
    node_type       VARCHAR(50) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    input_data      JSONB,
    output_data     JSONB,
    error_message   TEXT,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    duration_ms     INTEGER,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wf_step_execution ON workflow_step_executions(execution_id);
CREATE INDEX idx_wf_step_node ON workflow_step_executions(execution_id, node_id);

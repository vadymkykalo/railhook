-- =============================================
-- Workflow Reliability Improvements
-- =============================================

-- 1. Recursion depth tracking
ALTER TABLE workflow_executions ADD COLUMN depth INTEGER NOT NULL DEFAULT 0;

-- 2. Idempotency: prevent same event triggering same workflow twice
CREATE UNIQUE INDEX idx_wf_exec_idempotent
    ON workflow_executions(workflow_id, trigger_event_id)
    WHERE trigger_event_id IS NOT NULL;

-- 3. Stuck execution recovery index (find RUNNING executions older than threshold)
CREATE INDEX idx_wf_exec_stuck
    ON workflow_executions(status, started_at)
    WHERE status = 'RUNNING';

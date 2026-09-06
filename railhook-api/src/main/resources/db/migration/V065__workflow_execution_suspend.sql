-- A delay node used to be a Thread.sleep on the workflow pool.
--
-- That pool is core-size 4 / max-size 8 / queue 50, and a delay node may be configured up to
-- 300 seconds. Eight of them — one badly-configured workflow, or eight ordinary ones that
-- happen to overlap — occupied every thread in the deployment for five minutes, during which
-- no workflow belonging to any organization ran at all. The blocked threads were not doing
-- work; they were waiting for a clock.
--
-- So the execution suspends instead. These three columns are everything needed to put it back
-- together later, and no more:
--
--   resume_at    when the delay expires. Also the only thing the resume job polls on.
--   resume_state the outputs produced so far, the nodes already skipped, and which node to
--                continue from. JSONB rather than a table, because it is opaque to SQL — it is
--                read exactly once, by the engine that wrote it, and never queried into.
--   working_ms   time actually spent executing nodes, accumulated across suspensions.
--
-- working_ms exists because the global execution timeout (workflow.execution.max-ms) is a
-- budget for *work*. Measuring it as wall-clock from started_at would make a 5-minute delay
-- blow a 60-second budget every time — a workflow with a delay node in it could never finish.
-- Only the running segments count.
--
-- All three are nullable with no default: an execution that never suspends never sets them,
-- and a rolling deploy where the old code inserts without them is fine.
--
-- WAITING joins RUNNING/COMPLETED/FAILED/CANCELLED in the status column, which is already a
-- VARCHAR(50) — no constraint to widen. Note for anyone reading status counts: a WAITING
-- execution is neither finished nor stuck, and WorkflowExecutionRecoveryJob deliberately
-- ignores it (it sweeps RUNNING only, so a suspended execution is not mistaken for a hung one).

ALTER TABLE workflow_executions
    ADD COLUMN resume_at    TIMESTAMP,
    ADD COLUMN resume_state JSONB,
    ADD COLUMN working_ms   BIGINT;

-- The resume job's only query: due executions, oldest first. Partial, because the vast
-- majority of rows are finished executions that will never be WAITING again.
CREATE INDEX idx_wf_exec_resume_due
    ON workflow_executions (resume_at)
    WHERE status = 'WAITING';

COMMENT ON COLUMN workflow_executions.resume_at IS
    'When a suspended execution becomes due. Null unless status = WAITING.';
COMMENT ON COLUMN workflow_executions.resume_state IS
    'Engine-private snapshot: node outputs so far, skipped nodes, and the node to resume from. '
    'Opaque to SQL — written and read only by WorkflowEngine.';
COMMENT ON COLUMN workflow_executions.working_ms IS
    'Milliseconds spent actually executing nodes, accumulated across suspensions. The global '
    'execution timeout is a budget for work, so time spent suspended must not count against it.';

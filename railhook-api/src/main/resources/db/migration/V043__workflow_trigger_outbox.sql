-- Durable outbox for workflow triggers.
-- Written in the same transaction as the event + deliveries.
-- A poller picks up PENDING rows and executes workflows.
-- Guarantees at-least-once workflow triggering even after crash/restart.

CREATE TABLE workflow_trigger_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID        NOT NULL,
    event_id        UUID        NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    event_payload   TEXT,
    depth           INT         NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT fk_wf_trigger_event FOREIGN KEY (event_id) REFERENCES events(id)
);

CREATE INDEX idx_wf_trigger_outbox_pending ON workflow_trigger_outbox (status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_wf_trigger_outbox_event ON workflow_trigger_outbox (event_id);

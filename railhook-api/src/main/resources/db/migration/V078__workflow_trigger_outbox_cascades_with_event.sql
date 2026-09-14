-- A workflow trigger row now goes when its event goes.
--
-- V043 declared workflow_trigger_outbox.event_id with no ON DELETE, so any trigger row still
-- present blocked the deletion of its event. Outbox cleanup only ever collects DONE rows; a
-- trigger that exhausted its retries stays FAILED forever, so the first expired event carrying
-- one failed the nightly retention delete, and because retention ran as a single transaction
-- everything it had already removed that night was rolled back with it. The same happened the
-- next night, and every night after.
--
-- Cascade rather than excluding such events from retention: a trigger row exists only to
-- announce its event to the workflow engine, the same way a delivery exists only to carry it to
-- an endpoint, and deliveries already cascade from events (V001). Excluding the event would keep
-- it, and its payload, past the retention the plan promises for as long as a dead trigger row
-- happens to exist. A PENDING or PROCESSING trigger older than any retention window is not work
-- that is still coming — the poller claims PENDING rows within seconds and the stall sweep
-- recovers PROCESSING ones within the hour.
--
-- Added NOT VALID and validated separately so the brief lock on events is taken only for the
-- catalog change; the validation scan of workflow_trigger_outbox does not block writes to events.
ALTER TABLE workflow_trigger_outbox DROP CONSTRAINT IF EXISTS fk_wf_trigger_event;

ALTER TABLE workflow_trigger_outbox
    ADD CONSTRAINT fk_wf_trigger_event FOREIGN KEY (event_id) REFERENCES events (id)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE workflow_trigger_outbox VALIDATE CONSTRAINT fk_wf_trigger_event;

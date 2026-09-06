-- Denormalise the owning organization onto every tenant-scoped table.
--
-- ADR-0006 moves "is this row inside the caller's organization?" out of ~186 service method
-- signatures and into the repository layer: Hibernate's @TenantId adds
-- `organization_id = <current tenant>` to every query, including find-by-id, so a handler
-- cannot forget the check and a new repository method inherits it. That needs a real column
-- on each table -- a discriminator cannot be a join.
--
-- Most of these tables reach their organization through `project_id`, and thirteen of them
-- only through a parent row. Both are denormalised here rather than resolved at query time:
-- a filter that costs a join per query is a filter people route around.
--
-- NOT NULL from the start. The usual reason to defer it -- a rolling deploy where instances
-- predating the column are still inserting rows -- does not apply: this platform has no
-- production deployment yet, so there is no old writer to break and no accumulated data to
-- backfill in anger. Deferring it would mean shipping a nullable tenant column and remembering
-- to tighten it later, which is exactly the kind of half-applied invariant this migration
-- exists to avoid.
--
-- A row that reaches these tables without an organization is a bug in the writer, and NOT NULL
-- is what turns it into a failed insert rather than a row nobody can ever see.
--
-- No new indexes. Every query carrying the new predicate already carries a selective one --
-- the primary key, `project_id`, `endpoint_id` -- and Postgres filters the handful of matching
-- rows on `organization_id` afterwards. Adding 31 single-column indexes on a low-cardinality
-- column would cost write throughput on the hot delivery path and buy nothing.
--
-- Two tables are deliberately absent: `outbox_messages` and `workflow_trigger_outbox`. They
-- are internal queues drained by a poller under the system tenant, never a resource a request
-- reads back, and `outbox_messages.project_id` is nullable -- there is no organization to
-- backfill for an org-level message. See ADR-0006.

-- ── Tables one join from projects ────────────────────────────────────────────────────────

ALTER TABLE alert_events        ADD COLUMN organization_id UUID;
ALTER TABLE alert_rules         ADD COLUMN organization_id UUID;
ALTER TABLE api_keys            ADD COLUMN organization_id UUID;
ALTER TABLE endpoints           ADD COLUMN organization_id UUID;
ALTER TABLE events              ADD COLUMN organization_id UUID;
ALTER TABLE event_type_catalog  ADD COLUMN organization_id UUID;
ALTER TABLE incidents           ADD COLUMN organization_id UUID;
ALTER TABLE incoming_sources    ADD COLUMN organization_id UUID;
ALTER TABLE pii_masking_rules   ADD COLUMN organization_id UUID;
ALTER TABLE replay_sessions     ADD COLUMN organization_id UUID;
ALTER TABLE rule_execution_log  ADD COLUMN organization_id UUID;
ALTER TABLE rules               ADD COLUMN organization_id UUID;
ALTER TABLE shared_debug_links  ADD COLUMN organization_id UUID;
ALTER TABLE subscriptions       ADD COLUMN organization_id UUID;
ALTER TABLE test_endpoints      ADD COLUMN organization_id UUID;
ALTER TABLE transformations     ADD COLUMN organization_id UUID;
ALTER TABLE usage_daily         ADD COLUMN organization_id UUID;
ALTER TABLE workflows           ADD COLUMN organization_id UUID;

UPDATE alert_events       t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE alert_rules        t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE api_keys           t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE endpoints          t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE events             t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE event_type_catalog t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE incidents          t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE incoming_sources   t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE pii_masking_rules  t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE replay_sessions    t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE rule_execution_log t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE rules              t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE shared_debug_links t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE subscriptions      t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE test_endpoints     t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE transformations    t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE usage_daily        t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;
UPDATE workflows          t SET organization_id = p.organization_id FROM projects p WHERE p.id = t.project_id;

-- ── Tables that reach a tenant only through a parent row ─────────────────────────────────
--
-- Ordered so each backfill reads a column the statement above it has already filled:
-- endpoints → deliveries → delivery_attempts, incoming_sources → incoming_events →
-- incoming_forward_attempts, workflows → workflow_executions → workflow_step_executions.

ALTER TABLE captured_requests          ADD COLUMN organization_id UUID;
ALTER TABLE deliveries                 ADD COLUMN organization_id UUID;
ALTER TABLE delivery_attempts          ADD COLUMN organization_id UUID;
ALTER TABLE event_schema_version       ADD COLUMN organization_id UUID;
ALTER TABLE incident_timeline          ADD COLUMN organization_id UUID;
ALTER TABLE incoming_destinations      ADD COLUMN organization_id UUID;
ALTER TABLE incoming_events            ADD COLUMN organization_id UUID;
ALTER TABLE incoming_forward_attempts  ADD COLUMN organization_id UUID;
ALTER TABLE rule_actions               ADD COLUMN organization_id UUID;
ALTER TABLE schema_change              ADD COLUMN organization_id UUID;
ALTER TABLE workflow_executions        ADD COLUMN organization_id UUID;
ALTER TABLE workflow_step_executions   ADD COLUMN organization_id UUID;
ALTER TABLE billing_subscription_events ADD COLUMN organization_id UUID;

UPDATE captured_requests t SET organization_id = te.organization_id
    FROM test_endpoints te WHERE te.id = t.test_endpoint_id;

UPDATE deliveries t SET organization_id = e.organization_id
    FROM endpoints e WHERE e.id = t.endpoint_id;

UPDATE delivery_attempts t SET organization_id = d.organization_id
    FROM deliveries d WHERE d.id = t.delivery_id;

UPDATE event_schema_version t SET organization_id = c.organization_id
    FROM event_type_catalog c WHERE c.id = t.event_type_id;

UPDATE incident_timeline t SET organization_id = i.organization_id
    FROM incidents i WHERE i.id = t.incident_id;

UPDATE incoming_destinations t SET organization_id = s.organization_id
    FROM incoming_sources s WHERE s.id = t.incoming_source_id;

UPDATE incoming_events t SET organization_id = s.organization_id
    FROM incoming_sources s WHERE s.id = t.incoming_source_id;

UPDATE incoming_forward_attempts t SET organization_id = ie.organization_id
    FROM incoming_events ie WHERE ie.id = t.incoming_event_id;

UPDATE rule_actions t SET organization_id = r.organization_id
    FROM rules r WHERE r.id = t.rule_id;

UPDATE schema_change t SET organization_id = c.organization_id
    FROM event_type_catalog c WHERE c.id = t.event_type_id;

UPDATE workflow_executions t SET organization_id = w.organization_id
    FROM workflows w WHERE w.id = t.workflow_id;

UPDATE workflow_step_executions t SET organization_id = x.organization_id
    FROM workflow_executions x WHERE x.id = t.execution_id;

UPDATE billing_subscription_events t SET organization_id = s.organization_id
    FROM billing_subscriptions s WHERE s.id = t.subscription_id;

-- ── Tighten, now that every row has a value ──────────────────────────────────────────────

ALTER TABLE alert_events ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE alert_rules ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE api_keys ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE endpoints ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE events ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE event_type_catalog ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incidents ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incoming_sources ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE pii_masking_rules ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE replay_sessions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE rule_execution_log ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE rules ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE shared_debug_links ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE subscriptions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE test_endpoints ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE transformations ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE usage_daily ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE workflows ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE captured_requests ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE deliveries ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE delivery_attempts ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE event_schema_version ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incident_timeline ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incoming_destinations ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incoming_events ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE incoming_forward_attempts ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE rule_actions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE schema_change ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE workflow_executions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE workflow_step_executions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE billing_subscription_events ALTER COLUMN organization_id SET NOT NULL;

COMMENT ON COLUMN deliveries.organization_id IS
    'Denormalised tenant discriminator (ADR-0006). Hibernate @TenantId filters every query on it; the worker copies it from the parent row when inserting.';
COMMENT ON COLUMN delivery_attempts.organization_id IS
    'Denormalised tenant discriminator (ADR-0006). Written by the worker from the Delivery it is attempting.';

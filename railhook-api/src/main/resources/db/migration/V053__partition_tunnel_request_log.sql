-- Convert tunnel_request_log to declarative RANGE partitioning by created_at
-- (weekly). Retention here is only 7 days (data-retention.tunnel-request-log-retention-days)
-- — monthly partitions would routinely hold 3-4x the intended retention window before a
-- whole partition became droppable, so this table uses a tighter, weekly grain instead of
-- the monthly grain used for delivery_attempts.
--
-- Same attach-legacy-as-one-partition technique as V052 (see the extensive comment there
-- for why this is safe on a live, populated table). No inbound foreign keys reference
-- tunnel_request_log, and nothing
-- else in the schema references its id, so widening the PK to (id, created_at) has zero
-- ripple into other tables.

ALTER TABLE tunnel_request_log RENAME TO tunnel_request_log_legacy;
ALTER TABLE tunnel_request_log_legacy RENAME CONSTRAINT tunnel_request_log_pkey TO tunnel_request_log_legacy_pkey;
ALTER INDEX idx_tunnel_req_log_session RENAME TO idx_tunnel_req_log_legacy_session;
ALTER INDEX idx_tunnel_req_log_org RENAME TO idx_tunnel_req_log_legacy_org;
ALTER INDEX idx_tunnel_req_log_slug RENAME TO idx_tunnel_req_log_legacy_slug;
ALTER INDEX idx_tunnel_req_log_created RENAME TO idx_tunnel_req_log_legacy_created;
ALTER INDEX idx_tunnel_req_log_errors RENAME TO idx_tunnel_req_log_legacy_errors;

CREATE TABLE tunnel_request_log (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    tunnel_session_id   UUID        NOT NULL,
    organization_id     UUID        NOT NULL,
    slug                VARCHAR(64) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    method              VARCHAR(10) NOT NULL,
    path                VARCHAR(2048),
    query_string        VARCHAR(2048),
    request_headers     JSONB,
    request_body_size   INTEGER     DEFAULT 0,
    response_status     INTEGER,
    response_headers    JSONB,
    response_body_size  INTEGER     DEFAULT 0,
    duration_ms         INTEGER,
    error               VARCHAR(512),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE tunnel_request_log IS 'Tunnel request log for debugging and observability. Partitioned weekly by created_at; retention drops whole partitions (PartitionMaintenanceService) instead of DELETEing rows.';

CREATE INDEX idx_tunnel_req_log_session ON tunnel_request_log (tunnel_session_id);
CREATE INDEX idx_tunnel_req_log_org     ON tunnel_request_log (organization_id);
CREATE INDEX idx_tunnel_req_log_slug    ON tunnel_request_log (slug);
CREATE INDEX idx_tunnel_req_log_created ON tunnel_request_log (created_at);
CREATE INDEX idx_tunnel_req_log_errors  ON tunnel_request_log (slug, created_at) WHERE error IS NOT NULL;

DO $$
DECLARE
    cutover TIMESTAMPTZ := date_trunc('week', now());
    period_start TIMESTAMPTZ;
    period_end TIMESTAMPTZ;
    i INT;
BEGIN
    EXECUTE format(
        'ALTER TABLE tunnel_request_log_legacy ADD CONSTRAINT tunnel_request_log_legacy_created_at_check CHECK (created_at < %L) NOT VALID',
        cutover
    );
    EXECUTE 'ALTER TABLE tunnel_request_log_legacy VALIDATE CONSTRAINT tunnel_request_log_legacy_created_at_check';

    -- Same PK-widening as V052 (see its comment for the rationale and the production-safe
    -- CONCURRENTLY alternative).
    EXECUTE 'ALTER TABLE tunnel_request_log_legacy DROP CONSTRAINT tunnel_request_log_legacy_pkey';
    EXECUTE 'ALTER TABLE tunnel_request_log_legacy ADD CONSTRAINT tunnel_request_log_legacy_pkey PRIMARY KEY (id, created_at)';

    EXECUTE format(
        'ALTER TABLE tunnel_request_log ATTACH PARTITION tunnel_request_log_legacy FOR VALUES FROM (MINVALUE) TO (%L)',
        cutover
    );

    -- Forward partitions: current week + next 2 (PartitionMaintenanceService keeps
    -- extending this window daily; these three are just the initial seed).
    FOR i IN 0..2 LOOP
        period_start := cutover + (i || ' weeks')::interval;
        period_end := cutover + ((i + 1) || ' weeks')::interval;
        EXECUTE format(
            'CREATE TABLE tunnel_request_log_y%s_w%s PARTITION OF tunnel_request_log FOR VALUES FROM (%L) TO (%L)',
            to_char(period_start, 'IYYY'),
            to_char(period_start, 'IW'),
            period_start,
            period_end
        );
    END LOOP;
END $$;

CREATE TABLE tunnel_request_log_default PARTITION OF tunnel_request_log DEFAULT;

-- Convert delivery_attempts to declarative RANGE partitioning by created_at
-- (monthly). This is the single biggest disk/vacuum cost in the schema — it stores
-- full request/response bodies and headers per attempt — and was pruned entirely by
-- DELETE (DataRetentionService.cleanupOldDeliveryAttempts, 90-day global cutoff),
-- which means every retention run scans and deletes rows across the whole
-- accumulated table and leaves bloat behind for autovacuum to fight.
--
-- TECHNIQUE (safe for a live, populated table): this migration does NOT rewrite
-- existing rows — that would mean copying the entire table inside one Flyway
-- transaction, which does not scale and holds locks for however long the copy takes.
-- Instead:
--   1. Rename the existing table (and the constraint/index names it holds) out of the
--      way — ALTER TABLE/INDEX ... RENAME is a metadata-only operation, effectively
--      instant regardless of table size.
--   2. Create a new partitioned table named delivery_attempts.
--   3. Attach the renamed table as the partition covering everything strictly before
--      the start of the current month — all pre-cutover history stays exactly where
--      it physically is, just reachable through the partitioned parent now.
--   4. Create partitions for the current month plus the next 2 months, and a DEFAULT
--      partition as a safety net so inserts never fail outright if the partition
--      maintenance job (PartitionMaintenanceService) falls behind.
--
-- On a populated database, pre-validate the attach constraint ahead of the deploy
-- window so the ATTACH itself is instant.
--
-- WHY THE PRIMARY KEY WIDENS: Postgres requires every unique/primary-key index on a
-- partitioned table to include the partition key column. `id` remains globally unique
-- in practice — both JPA copies of this entity use
-- `@GeneratedValue(strategy = GenerationType.UUID)`, which assigns the UUID
-- client-side before every INSERT — so widening PRIMARY KEY to (id, created_at), and
-- the (delivery_id, attempt_number) uniqueness constraint to include created_at,
-- changes nothing observable to either application: every query still keys on
-- id / delivery_id directly, and Hibernate's `ddl-auto: validate` only checks that
-- mapped columns exist with compatible types, not the shape of PK/unique constraints,
-- so no entity changes are required in api or worker.
--
-- RETENTION: dropping a whole partition (O(1), just unlinks files) replaces the
-- coarse 90-day sweep (cleanupOldDeliveryAttempts) — see PartitionMaintenanceService.
-- It does NOT replace the two finer-grained jobs that stay row-level DELETEs because
-- they are not simple time cutoffs: cleanupOldSuccessfulAttempts (14-day cutoff,
-- filtered by 2xx status only) and enforcePerDeliveryAttemptLimits (keep last N per
-- delivery, not time-based at all). Both keep working unchanged and now run against a
-- much smaller live partition instead of the whole accumulated table.

-- 1. Free up the names currently held by the unpartitioned table.
ALTER TABLE delivery_attempts RENAME TO delivery_attempts_legacy;
ALTER TABLE delivery_attempts_legacy RENAME CONSTRAINT delivery_attempts_pkey TO delivery_attempts_legacy_pkey;
ALTER INDEX idx_delivery_attempts_delivery_id RENAME TO idx_delivery_attempts_legacy_delivery_id;
ALTER INDEX idx_delivery_attempts_created_at RENAME TO idx_delivery_attempts_legacy_created_at;
ALTER INDEX idx_delivery_attempts_unique_attempt RENAME TO idx_delivery_attempts_legacy_unique_attempt;
ALTER INDEX idx_delivery_attempts_cleanup RENAME TO idx_delivery_attempts_legacy_cleanup;

-- 2. New partitioned parent — same columns/comment as V001/V035/V045, minus
--    idx_delivery_attempts_created_at (dropped as redundant below: it is a strict
--    left-prefix of idx_delivery_attempts_cleanup(created_at, http_status_code) with
--    no narrowing WHERE clause, so any created_at-range query the narrow index could
--    serve, the wide one serves too).
CREATE TABLE delivery_attempts (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    http_status_code INTEGER,
    response_body TEXT,
    error_message TEXT,
    duration_ms INTEGER,
    request_headers JSONB,
    request_body TEXT,
    response_headers JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE delivery_attempts IS 'Stores webhook delivery attempt details with request/response data. Partitioned monthly by created_at; retention drops whole partitions (PartitionMaintenanceService) instead of DELETEing rows. Successful (2xx) attempts are additionally pruned earlier via row-level DELETE at 14d (data-retention.successful-attempts-retention-days), which is finer-grained than a monthly partition boundary.';

-- Indexes on the parent auto-propagate to every current AND future partition (PG 11+).
CREATE INDEX idx_delivery_attempts_delivery_id ON delivery_attempts (delivery_id);
CREATE UNIQUE INDEX idx_delivery_attempts_unique_attempt ON delivery_attempts (delivery_id, attempt_number, created_at);
CREATE INDEX idx_delivery_attempts_cleanup ON delivery_attempts (created_at, http_status_code);

-- 3 & 4. Attach pre-cutover history as one partition, then create forward partitions.
-- date_trunc()/now() are not IMMUTABLE so they can't appear in a CHECK constraint
-- expression directly — compute the boundary once in PL/pgSQL and splice the literal
-- value into the DDL text instead.
DO $$
DECLARE
    cutover TIMESTAMP := date_trunc('month', now());
    period_start TIMESTAMP;
    period_end TIMESTAMP;
    i INT;
BEGIN
    -- Matching, already-validated CHECK constraint lets ATTACH PARTITION skip
    -- re-scanning delivery_attempts_legacy to verify the bound. VALIDATE CONSTRAINT
    -- takes SHARE UPDATE EXCLUSIVE (does not block reads/writes); ATTACH PARTITION
    -- itself then only needs a brief ACCESS EXCLUSIVE on the new, empty parent.
    EXECUTE format(
        'ALTER TABLE delivery_attempts_legacy ADD CONSTRAINT delivery_attempts_legacy_created_at_check CHECK (created_at < %L) NOT VALID',
        cutover
    );
    EXECUTE 'ALTER TABLE delivery_attempts_legacy VALIDATE CONSTRAINT delivery_attempts_legacy_created_at_check';

    -- A partition must carry its own local index backing every unique/PK constraint
    -- the parent declares, with matching columns — Postgres does not synthesize one on
    -- ATTACH. delivery_attempts_legacy still has its pre-partitioning single-column PK
    -- (renamed above), which both (a) doesn't match the parent's (id, created_at) PK
    -- and (b) would collide with a second PK constraint if left in place, so swap it
    -- for a composite one first. On a large production table, pre-build this as
    -- `CREATE UNIQUE INDEX CONCURRENTLY ... ON delivery_attempts_legacy (id, created_at)`
    -- ahead of the deploy window and attach it with `ADD CONSTRAINT ... PRIMARY KEY
    -- USING INDEX ...` instead,
    -- since the plain form below takes ACCESS EXCLUSIVE for the whole index build.
    EXECUTE 'ALTER TABLE delivery_attempts_legacy DROP CONSTRAINT delivery_attempts_legacy_pkey';
    EXECUTE 'ALTER TABLE delivery_attempts_legacy ADD CONSTRAINT delivery_attempts_legacy_pkey PRIMARY KEY (id, created_at)';
    -- Same reasoning for the (delivery_id, attempt_number, created_at) uniqueness
    -- index: the legacy table's existing one is a bare unique index (not a named
    -- constraint — see V045) on the old, narrower (delivery_id, attempt_number)
    -- column set, and needs to be widened to match.
    EXECUTE 'DROP INDEX idx_delivery_attempts_legacy_unique_attempt';
    EXECUTE 'CREATE UNIQUE INDEX idx_delivery_attempts_legacy_unique_attempt ON delivery_attempts_legacy (delivery_id, attempt_number, created_at)';

    EXECUTE format(
        'ALTER TABLE delivery_attempts ATTACH PARTITION delivery_attempts_legacy FOR VALUES FROM (MINVALUE) TO (%L)',
        cutover
    );

    -- Forward partitions: current month + next 2 (PartitionMaintenanceService keeps
    -- extending this window daily; these three are just the initial seed).
    FOR i IN 0..2 LOOP
        period_start := cutover + (i || ' months')::interval;
        period_end := cutover + ((i + 1) || ' months')::interval;
        EXECUTE format(
            'CREATE TABLE delivery_attempts_y%s_m%s PARTITION OF delivery_attempts FOR VALUES FROM (%L) TO (%L)',
            to_char(period_start, 'YYYY'),
            to_char(period_start, 'MM'),
            period_start,
            period_end
        );
    END LOOP;
END $$;

-- Safety net: catches anything outside the created partitions (clock skew, a missed
-- partition-maintenance run) so inserts fail loudly-in-metrics rather than fail
-- outright. PartitionMaintenanceService logs a warning if this partition ever holds
-- rows, since that means maintenance fell behind and needs attention.
CREATE TABLE delivery_attempts_default PARTITION OF delivery_attempts DEFAULT;

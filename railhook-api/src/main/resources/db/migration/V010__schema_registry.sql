-- Schema Registry: Contract testing for webhooks
-- Adds event type catalog, versioned JSON schemas, schema change tracking,
-- and per-project settings to enable/configure schema validation.

-- ── Project settings for schema validation ──
ALTER TABLE projects
    ADD COLUMN schema_validation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN schema_validation_policy  VARCHAR(10) NOT NULL DEFAULT 'WARN';
-- WARN  = validate & log violations, but still deliver
-- BLOCK = validate & reject delivery on violation

COMMENT ON COLUMN projects.schema_validation_enabled IS 'Enable contract-based payload validation for this project';
COMMENT ON COLUMN projects.schema_validation_policy  IS 'WARN = log violations, BLOCK = reject delivery';

-- ── Event Type Catalog ──
CREATE TABLE event_type_catalog (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_event_type_catalog_project_name UNIQUE (project_id, name)
);

CREATE INDEX idx_event_type_catalog_project ON event_type_catalog(project_id);

-- ── Event Schema Version ──
CREATE TABLE event_schema_version (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type_id      UUID         NOT NULL REFERENCES event_type_catalog(id) ON DELETE CASCADE,
    version            INT          NOT NULL,
    schema_json        JSONB        NOT NULL,
    fingerprint        VARCHAR(64)  NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    compatibility_mode VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    description        TEXT,
    created_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_schema_version_type_version UNIQUE (event_type_id, version)
);

CREATE INDEX idx_event_schema_version_type   ON event_schema_version(event_type_id);
CREATE INDEX idx_event_schema_version_status ON event_schema_version(event_type_id, status);

COMMENT ON COLUMN event_schema_version.status             IS 'DRAFT, ACTIVE, DEPRECATED';
COMMENT ON COLUMN event_schema_version.compatibility_mode IS 'NONE, BACKWARD, FORWARD, FULL';
COMMENT ON COLUMN event_schema_version.fingerprint        IS 'SHA-256 of normalized schema to detect duplicates';

-- ── Schema Change (diff between versions) ──
CREATE TABLE schema_change (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type_id    UUID         NOT NULL REFERENCES event_type_catalog(id) ON DELETE CASCADE,
    from_version_id  UUID         REFERENCES event_schema_version(id) ON DELETE SET NULL,
    to_version_id    UUID         NOT NULL REFERENCES event_schema_version(id) ON DELETE CASCADE,
    change_summary   JSONB        NOT NULL,
    breaking         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_schema_change_type ON schema_change(event_type_id);

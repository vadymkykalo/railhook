-- ============================================================
-- V083: Transformation version history
-- ============================================================
--
-- transformations.version has been incremented on every template edit since V023, but the
-- previous template was overwritten in place. The dashboard showed that counter as "v3", which
-- reads as a promise that v2 can be looked at and put back — and nothing could. This table is
-- what makes the counter true: one row per published template, never updated, never deleted
-- except by the per-transformation retention cap.
--
-- Only railhook-api maps this table. The worker resolves a Transformation to its *current*
-- template (TransformationCacheService) and has no use for the history, so it deliberately keeps
-- no entity copy — EntityMappingParityIntegrationTest only covers tables both modules map.

CREATE TABLE transformation_versions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    transformation_id     UUID         NOT NULL REFERENCES transformations(id) ON DELETE CASCADE,
    version               INTEGER      NOT NULL,
    template              TEXT         NOT NULL,
    restored_from_version INTEGER,
    created_by            UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_transformation_version UNIQUE (transformation_id, version)
);

-- The list view reads one transformation's history newest first; the unique constraint's index
-- leads on transformation_id, so this adds only the descending order.
CREATE INDEX idx_transformation_versions_history
    ON transformation_versions(transformation_id, version DESC);

COMMENT ON TABLE  transformation_versions IS
    'Append-only history of published transformation templates. A restore writes a new row rather than removing later ones.';
COMMENT ON COLUMN transformation_versions.restored_from_version IS
    'Set when this version was published by restoring an earlier one, naming that earlier version.';
COMMENT ON COLUMN transformation_versions.created_by IS
    'The user who published it; NULL for an API key, for the platform, and for a user since erased.';

-- Backfill: every transformation that already exists gets its current template as its current
-- version, so an existing transformation opens on a history with something in it rather than on
-- an empty list that reads like data loss. created_by is unknowable in retrospect and stays NULL.
INSERT INTO transformation_versions (organization_id, transformation_id, version, template, created_at)
SELECT t.organization_id, t.id, t.version, t.template, t.updated_at
FROM transformations t;

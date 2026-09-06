-- ============================================================
-- V023: Transformations — reusable payload mapping templates
-- ============================================================

CREATE TABLE transformations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID        NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    template        TEXT        NOT NULL,
    version         INTEGER     NOT NULL DEFAULT 1,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_transformation_name_project UNIQUE (project_id, name)
);

CREATE INDEX idx_transformations_project_id ON transformations(project_id);

-- Link outgoing subscriptions to a reusable transformation (nullable — keeps backward compat with inline payloadTemplate)
ALTER TABLE subscriptions
    ADD COLUMN transformation_id UUID REFERENCES transformations(id) ON DELETE SET NULL;

CREATE INDEX idx_subscriptions_transformation_id ON subscriptions(transformation_id);

-- Link incoming destinations to a reusable transformation (nullable — keeps backward compat with inline payloadTransform)
ALTER TABLE incoming_destinations
    ADD COLUMN transformation_id UUID REFERENCES transformations(id) ON DELETE SET NULL;

CREATE INDEX idx_incoming_destinations_transformation_id ON incoming_destinations(transformation_id);

-- Deliveries: store resolved transformation template at creation time (snapshot)
ALTER TABLE deliveries
    ADD COLUMN transformation_id UUID;

CREATE INDEX idx_deliveries_transformation_id ON deliveries(transformation_id);

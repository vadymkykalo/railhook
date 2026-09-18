-- Consumers, and the portal sessions a customer opens for them.
--
-- A Consumer is one of the customer's own users: whoever receives the customer's webhooks and
-- would otherwise have to ask the customer to register, change or debug an Endpoint for them.
-- Grouping Endpoints by Consumer is what lets the customer hand that user a view of their own
-- Endpoints and Deliveries and nothing else. external_id is the customer's own key for the user,
-- unique per project, so their backend can find the Consumer without storing Railhook's id.
--
-- A Consumer is deleted outright rather than soft-deleted: its Endpoints are soft-deleted by the
-- application first, and keep their Deliveries' history with consumer_id cleared.
CREATE TABLE consumers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id      UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    external_id     VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_consumers_project_external_id UNIQUE (project_id, external_id)
);

COMMENT ON TABLE consumers IS
    'The customer''s own users, each grouping the Endpoints registered for them; see the customer portal';

-- Nullable: an Endpoint the customer registered for itself belongs to no Consumer, and every
-- Endpoint that existed before this migration is one of those.
ALTER TABLE endpoints ADD COLUMN consumer_id UUID REFERENCES consumers (id) ON DELETE SET NULL;

CREATE INDEX idx_endpoints_consumer ON endpoints (consumer_id) WHERE consumer_id IS NOT NULL;

-- A portal session is a bearer credential for one Consumer, handed by the customer's backend to
-- a browser that belongs to no Railhook user. Only the SHA-256 of its token is stored, as with API
-- keys. allowed_origin, when set, is the one https origin the portal may be embedded in.
CREATE TABLE portal_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id      UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    consumer_id     UUID         NOT NULL REFERENCES consumers (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL,
    allowed_origin  VARCHAR(255),
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_portal_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_portal_sessions_consumer ON portal_sessions (consumer_id);
CREATE INDEX idx_portal_sessions_expires_at ON portal_sessions (expires_at);

COMMENT ON TABLE portal_sessions IS
    'Short-lived bearer sessions scoped to one Consumer; only the SHA-256 of each token is stored';

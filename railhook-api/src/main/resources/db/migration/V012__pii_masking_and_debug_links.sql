-- PII masking rules per project
CREATE TABLE pii_masking_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    rule_type       VARCHAR(20) NOT NULL,  -- BUILTIN or CUSTOM
    pattern_name    VARCHAR(100) NOT NULL, -- email, phone, card, custom field path
    json_path       VARCHAR(500),          -- JSON path expression for custom fields (e.g. $.user.ssn)
    mask_style      VARCHAR(20) NOT NULL DEFAULT 'PARTIAL', -- FULL, PARTIAL, HASH
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, pattern_name)
);

CREATE INDEX idx_pii_masking_rules_project ON pii_masking_rules(project_id);

-- Shared debug links (public, token-based, time-limited)
CREATE TABLE shared_debug_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    event_id        UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    token           VARCHAR(64) NOT NULL UNIQUE,
    created_by      UUID REFERENCES users(id),
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    view_count      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_shared_debug_links_token ON shared_debug_links(token);
CREATE INDEX idx_shared_debug_links_project ON shared_debug_links(project_id);
CREATE INDEX idx_shared_debug_links_event ON shared_debug_links(event_id);

-- Seed default built-in PII rules when a project is created (done in application code)

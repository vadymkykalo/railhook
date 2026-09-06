CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action          VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(50)  NOT NULL,
    resource_id     UUID,
    user_id         UUID,
    organization_id UUID,
    status          VARCHAR(20)  NOT NULL,
    error_message   TEXT,
    duration_ms     INTEGER,
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_org_id ON audit_log(organization_id);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);

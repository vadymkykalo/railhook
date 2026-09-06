-- Tunnel request log for debugging and observability
CREATE TABLE tunnel_request_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tunnel_session_id UUID        NOT NULL,
    organization_id UUID          NOT NULL,
    slug            VARCHAR(64)   NOT NULL,
    request_id      VARCHAR(64)   NOT NULL,
    method          VARCHAR(10)   NOT NULL,
    path            VARCHAR(2048),
    query_string    VARCHAR(2048),
    request_headers JSONB,
    request_body_size INTEGER     DEFAULT 0,
    response_status INTEGER,
    response_headers JSONB,
    response_body_size INTEGER    DEFAULT 0,
    duration_ms     INTEGER,
    error           VARCHAR(512),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_tunnel_req_log_session ON tunnel_request_log (tunnel_session_id);
CREATE INDEX idx_tunnel_req_log_org     ON tunnel_request_log (organization_id);
CREATE INDEX idx_tunnel_req_log_slug    ON tunnel_request_log (slug);
CREATE INDEX idx_tunnel_req_log_created ON tunnel_request_log (created_at);
-- Partial index for errors only
CREATE INDEX idx_tunnel_req_log_errors  ON tunnel_request_log (slug, created_at) WHERE error IS NOT NULL;

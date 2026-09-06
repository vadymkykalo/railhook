-- =============================================
-- Incoming Webhooks — Schema
-- =============================================

-- 1. Incoming Sources (provider/integration config)
CREATE TABLE incoming_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    provider_type VARCHAR(50) NOT NULL DEFAULT 'GENERIC',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ingress_path_token VARCHAR(64) NOT NULL UNIQUE,
    verification_mode VARCHAR(30) NOT NULL DEFAULT 'NONE',
    hmac_secret_encrypted TEXT,
    hmac_secret_iv TEXT,
    hmac_header_name VARCHAR(255) DEFAULT 'X-Signature',
    hmac_signature_prefix VARCHAR(50) DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incoming_sources_project_id ON incoming_sources(project_id);
CREATE INDEX idx_incoming_sources_status ON incoming_sources(status) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX idx_incoming_sources_project_slug ON incoming_sources(project_id, slug);
CREATE INDEX idx_incoming_sources_ingress_token ON incoming_sources(ingress_path_token);

-- 2. Incoming Destinations (where to forward)
CREATE TABLE incoming_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incoming_source_id UUID NOT NULL REFERENCES incoming_sources(id) ON DELETE CASCADE,
    url VARCHAR(2048) NOT NULL,
    auth_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    auth_config_encrypted TEXT,
    auth_config_iv TEXT,
    custom_headers_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    retry_delays TEXT NOT NULL DEFAULT '60,300,900,3600,21600',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incoming_destinations_source_id ON incoming_destinations(incoming_source_id);
CREATE INDEX idx_incoming_destinations_enabled ON incoming_destinations(enabled) WHERE enabled = true;

-- 3. Incoming Events (received webhooks)
CREATE TABLE incoming_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incoming_source_id UUID NOT NULL REFERENCES incoming_sources(id) ON DELETE CASCADE,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(2048),
    query_params TEXT,
    headers_json TEXT,
    body_raw TEXT,
    body_sha256 VARCHAR(64),
    content_type VARCHAR(255),
    client_ip VARCHAR(45),
    user_agent VARCHAR(512),
    verified BOOLEAN,
    verification_error TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incoming_events_source_id ON incoming_events(incoming_source_id);
CREATE INDEX idx_incoming_events_received_at ON incoming_events(received_at DESC);
CREATE INDEX idx_incoming_events_request_id ON incoming_events(request_id);
CREATE INDEX idx_incoming_events_verified ON incoming_events(verified);
CREATE INDEX idx_incoming_events_source_received ON incoming_events(incoming_source_id, received_at DESC);

-- 4. Incoming Forward Attempts (delivery attempts to destination)
CREATE TABLE incoming_forward_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incoming_event_id UUID NOT NULL REFERENCES incoming_events(id) ON DELETE CASCADE,
    destination_id UUID NOT NULL REFERENCES incoming_destinations(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    response_code INTEGER,
    response_headers_json TEXT,
    response_body_snippet TEXT,
    error_message TEXT,
    next_retry_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incoming_fwd_attempts_event_id ON incoming_forward_attempts(incoming_event_id);
CREATE INDEX idx_incoming_fwd_attempts_dest_id ON incoming_forward_attempts(destination_id);
CREATE INDEX idx_incoming_fwd_attempts_status ON incoming_forward_attempts(status);
CREATE INDEX idx_incoming_fwd_attempts_retry ON incoming_forward_attempts(status, next_retry_at)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;
CREATE INDEX idx_incoming_fwd_attempts_event_dest ON incoming_forward_attempts(incoming_event_id, destination_id, attempt_number DESC);

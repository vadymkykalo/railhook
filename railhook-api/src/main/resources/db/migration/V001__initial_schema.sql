-- =============================================
-- Webhook Platform — Initial Schema
-- =============================================

-- 1. Users & Auth
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);

-- 2. Organizations
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_organizations_created_at ON organizations(created_at);

-- 3. Memberships
CREATE TABLE memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, organization_id)
);

CREATE INDEX idx_memberships_user_id ON memberships(user_id);
CREATE INDEX idx_memberships_organization_id ON memberships(organization_id);
CREATE INDEX idx_memberships_role ON memberships(role);

-- 4. Projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_projects_organization_id ON projects(organization_id);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_deleted_at ON projects(deleted_at) WHERE deleted_at IS NOT NULL;

-- 5. API Keys
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(16) NOT NULL,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX idx_api_keys_project_id ON api_keys(project_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_revoked_at ON api_keys(revoked_at) WHERE revoked_at IS NULL;

-- 6. Events
CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    event_type VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255),
    payload JSONB NOT NULL,
    sequence_number BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_events_project_id ON events(project_id);
CREATE INDEX idx_events_event_type ON events(event_type);
CREATE INDEX idx_events_created_at ON events(created_at);
CREATE UNIQUE INDEX idx_events_idempotency_key ON events(project_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- 7. Endpoints
CREATE TABLE endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    url VARCHAR(2048) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    rate_limit_per_second INTEGER,
    -- Secret (AES-GCM encrypted)
    secret_encrypted TEXT NOT NULL,
    secret_iv TEXT NOT NULL,
    -- Secret rotation
    secret_previous_encrypted TEXT,
    secret_previous_iv TEXT,
    secret_rotated_at TIMESTAMP,
    secret_rotation_grace_period_hours INTEGER DEFAULT 24,
    -- IP allowlist (comma-separated CIDR)
    allowed_source_ips TEXT,
    -- mTLS
    mtls_enabled BOOLEAN NOT NULL DEFAULT false,
    client_cert_encrypted TEXT,
    client_cert_iv TEXT,
    client_key_encrypted TEXT,
    client_key_iv TEXT,
    ca_cert TEXT,
    -- Endpoint verification (opt-in, default SKIPPED)
    verification_status VARCHAR(32) NOT NULL DEFAULT 'SKIPPED',
    verification_token VARCHAR(64),
    verification_attempted_at TIMESTAMP,
    verification_completed_at TIMESTAMP,
    verification_skip_reason VARCHAR(255),
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_endpoints_project_id ON endpoints(project_id);
CREATE INDEX idx_endpoints_enabled ON endpoints(enabled) WHERE enabled = true;
CREATE INDEX idx_endpoints_deleted_at ON endpoints(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_endpoints_mtls_enabled ON endpoints(mtls_enabled) WHERE mtls_enabled = true;
CREATE INDEX idx_endpoints_verification_status ON endpoints(verification_status);

-- 8. Subscriptions
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    event_type VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    -- Ordering
    ordering_enabled BOOLEAN NOT NULL DEFAULT false,
    -- Retry policy
    max_attempts INTEGER DEFAULT 7,
    timeout_seconds INTEGER DEFAULT 30,
    retry_delays TEXT DEFAULT '60,300,900,3600,21600,86400',
    -- Payload transformation
    payload_template TEXT,
    custom_headers TEXT,
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscriptions_project_id ON subscriptions(project_id);
CREATE INDEX idx_subscriptions_endpoint_id ON subscriptions(endpoint_id);
CREATE INDEX idx_subscriptions_event_type ON subscriptions(event_type);
CREATE INDEX idx_subscriptions_enabled ON subscriptions(enabled) WHERE enabled = true;
CREATE UNIQUE INDEX idx_subscriptions_unique ON subscriptions(endpoint_id, event_type);

-- 9. Deliveries
CREATE TABLE deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 7,
    -- Ordering
    sequence_number BIGINT,
    ordering_enabled BOOLEAN NOT NULL DEFAULT false,
    -- Retry policy (copied from subscription)
    timeout_seconds INTEGER DEFAULT 30,
    retry_delays TEXT DEFAULT '60,300,900,3600,21600,86400',
    -- Payload transformation (copied from subscription)
    payload_template TEXT,
    custom_headers TEXT,
    -- Timestamps
    next_retry_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    succeeded_at TIMESTAMP,
    failed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_deliveries_event_id ON deliveries(event_id);
CREATE INDEX idx_deliveries_endpoint_id ON deliveries(endpoint_id);
CREATE INDEX idx_deliveries_subscription_id ON deliveries(subscription_id);
CREATE INDEX idx_deliveries_status ON deliveries(status);
CREATE INDEX idx_deliveries_next_retry_at ON deliveries(next_retry_at) WHERE next_retry_at IS NOT NULL;
CREATE UNIQUE INDEX idx_deliveries_unique ON deliveries(event_id, endpoint_id, subscription_id);
-- Retry scheduler: status + next_retry_at composite
CREATE INDEX idx_deliveries_retry_query ON deliveries(status, next_retry_at) WHERE next_retry_at IS NOT NULL;
-- FIFO ordering indexes
CREATE INDEX idx_deliveries_endpoint_seq ON deliveries(endpoint_id, sequence_number) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_deliveries_endpoint_pending_seq ON deliveries(endpoint_id, sequence_number, created_at) WHERE status = 'PENDING' AND ordering_enabled = true;
CREATE UNIQUE INDEX idx_deliveries_endpoint_seq_unique ON deliveries(endpoint_id, sequence_number) WHERE ordering_enabled = true AND sequence_number IS NOT NULL;

-- 10. Delivery Attempts
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    http_status_code INTEGER,
    response_body TEXT,
    error_message TEXT,
    duration_ms INTEGER,
    request_headers JSONB,
    request_body TEXT,
    response_headers JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_delivery_attempts_delivery_id ON delivery_attempts(delivery_id);
CREATE INDEX idx_delivery_attempts_created_at ON delivery_attempts(created_at);
CREATE INDEX idx_delivery_attempts_delivery_attempt_number ON delivery_attempts(delivery_id, attempt_number DESC);

COMMENT ON TABLE delivery_attempts IS 'Stores webhook delivery attempt details with request/response data. Retention: 90 days or last 10 attempts per delivery.';

-- 11. Outbox (transactional outbox pattern)
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    kafka_topic VARCHAR(255) NOT NULL,
    kafka_key VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_messages_status ON outbox_messages(status);
CREATE INDEX idx_outbox_messages_created_at ON outbox_messages(created_at);
CREATE INDEX idx_outbox_messages_aggregate ON outbox_messages(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_messages_pending ON outbox_messages(created_at) WHERE status = 'PENDING';

-- 12. Test Endpoints (Request Bin)
CREATE TABLE test_endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    slug VARCHAR(12) NOT NULL UNIQUE,
    name VARCHAR(255),
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE captured_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_endpoint_id UUID NOT NULL REFERENCES test_endpoints(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(1024),
    query_string TEXT,
    headers TEXT,
    body TEXT,
    content_type VARCHAR(255),
    source_ip VARCHAR(45),
    user_agent VARCHAR(512),
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_test_endpoints_project ON test_endpoints(project_id);
CREATE INDEX idx_test_endpoints_slug ON test_endpoints(slug);
CREATE INDEX idx_test_endpoints_expires ON test_endpoints(expires_at);
CREATE INDEX idx_captured_requests_endpoint ON captured_requests(test_endpoint_id);
CREATE INDEX idx_captured_requests_received ON captured_requests(received_at DESC);

-- 13. ShedLock (distributed task locking)
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

CREATE INDEX idx_shedlock_lock_until ON shedlock(lock_until);

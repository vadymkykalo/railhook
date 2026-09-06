-- Tunnel sessions for CLI local webhook tunnels
CREATE TABLE tunnel_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    project_id      UUID,
    tunnel_token    VARCHAR(128) NOT NULL UNIQUE,
    public_slug     VARCHAR(64)  NOT NULL UNIQUE,
    local_port      INTEGER      NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_heartbeat  TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    client_info     VARCHAR(255)
);

CREATE INDEX idx_tunnel_sessions_org     ON tunnel_sessions (organization_id);
CREATE INDEX idx_tunnel_sessions_user    ON tunnel_sessions (user_id);
CREATE INDEX idx_tunnel_sessions_status  ON tunnel_sessions (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_tunnel_sessions_token   ON tunnel_sessions (tunnel_token);
CREATE INDEX idx_tunnel_sessions_slug    ON tunnel_sessions (public_slug);

-- Device auth codes for CLI login flow
CREATE TABLE device_auth_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_code     VARCHAR(128) NOT NULL UNIQUE,
    user_code       VARCHAR(16)  NOT NULL UNIQUE,
    user_id         UUID,
    organization_id UUID,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at     TIMESTAMPTZ
);

CREATE INDEX idx_device_auth_device_code ON device_auth_codes (device_code);
CREATE INDEX idx_device_auth_user_code   ON device_auth_codes (user_code);
CREATE INDEX idx_device_auth_status      ON device_auth_codes (status) WHERE status = 'PENDING';

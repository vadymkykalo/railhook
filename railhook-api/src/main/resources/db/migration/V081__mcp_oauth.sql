-- Sign-in for the MCP server, so an AI app that speaks the MCP authorization spec (claude.ai and
-- Claude Desktop connectors, ChatGPT) can connect with a browser consent instead of a pasted API
-- key. Railhook is its own OAuth 2.1 authorization server for /mcp; see McpOAuthService.
--
-- Three tables, because the three things live in different places on the tenant line:
--
--   oauth_clients                 an app that registered itself (RFC 7591). Belongs to nobody:
--                                 it registers before any person or organization is involved.
--   oauth_authorization_requests  one browser trip through /oauth/authorize, waiting for a person
--                                 to answer it. Also nobody's until it is answered.
--   oauth_grants                  what a person approved: one app, one project, one scope. Tenant
--                                 scoped like api_keys, which it stands in for on /mcp.
--
-- Every secret is stored as a SHA-256 hash, never as issued: client secrets, authorization codes,
-- access tokens and refresh tokens alike.

CREATE TABLE oauth_clients (
    id                         UUID PRIMARY KEY,
    client_id                  VARCHAR(64)  NOT NULL UNIQUE,
    -- Null for a public client (token_endpoint_auth_method "none"), which proves itself with PKCE.
    client_secret_hash         VARCHAR(64),
    token_endpoint_auth_method VARCHAR(32)  NOT NULL,
    client_name                VARCHAR(200) NOT NULL,
    client_uri                 VARCHAR(2000),
    -- One absolute URI per line. Matched exactly, character for character.
    redirect_uris              TEXT         NOT NULL,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE oauth_clients IS 'MCP apps registered through OAuth dynamic client registration (RFC 7591); not tenant-scoped.';

CREATE TABLE oauth_authorization_requests (
    id              UUID PRIMARY KEY,
    client_id       UUID          NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    redirect_uri    VARCHAR(2000) NOT NULL,
    code_challenge  VARCHAR(128)  NOT NULL,
    state           VARCHAR(1000),
    requested_scope VARCHAR(500),
    resource        VARCHAR(2000),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP     NOT NULL,
    -- Set once the request is answered either way; an answered request cannot be answered again.
    completed_at    TIMESTAMP
);

CREATE INDEX idx_oauth_authorization_requests_expires_at ON oauth_authorization_requests (expires_at);

COMMENT ON TABLE oauth_authorization_requests IS 'Pending /oauth/authorize requests waiting on the consent screen; short-lived and not tenant-scoped.';

CREATE TABLE oauth_grants (
    id                          UUID PRIMARY KEY,
    organization_id             UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    project_id                  UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    client_id                   UUID          NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    -- Who approved it. The grant is only as good as their membership: it stops working the moment
    -- they leave the organization, are suspended, or lose the role a READ_WRITE grant needs.
    user_id                     UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scope                       VARCHAR(20)   NOT NULL,
    redirect_uri                VARCHAR(2000) NOT NULL,
    code_challenge              VARCHAR(128)  NOT NULL,
    code_hash                   VARCHAR(64)   UNIQUE,
    code_expires_at             TIMESTAMP,
    code_used_at                TIMESTAMP,
    access_token_hash           VARCHAR(64)   UNIQUE,
    access_token_expires_at     TIMESTAMP,
    refresh_token_hash          VARCHAR(64)   UNIQUE,
    refresh_token_expires_at    TIMESTAMP,
    -- The refresh token this one replaced. Presenting it again means it was copied: the grant is
    -- revoked rather than letting two holders refresh side by side.
    previous_refresh_token_hash VARCHAR(64),
    created_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- When the code was first exchanged. A grant whose code never was is not in use yet,
    -- and is not listed.
    activated_at                TIMESTAMP,
    last_used_at                TIMESTAMP,
    revoked_at                  TIMESTAMP
);

CREATE INDEX idx_oauth_grants_project ON oauth_grants (project_id);
CREATE INDEX idx_oauth_grants_previous_refresh ON oauth_grants (previous_refresh_token_hash);

COMMENT ON TABLE oauth_grants IS 'An MCP app a person connected to one project with one scope; stands in for an API key on /mcp.';

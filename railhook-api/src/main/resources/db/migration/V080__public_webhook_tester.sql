-- The webhook tester on the public site (/tester): a URL anyone can make without an account,
-- which records what is sent to it for a day, so a developer can see what a provider really
-- sends before signing up.
--
-- It belongs to no organization, so there is no organization_id and no tenant scope: the slug
-- is the only identity, as it is for a test endpoint. The bounds that keep it from becoming an
-- open store live in PublicBinService (100 requests, 64 KB of body each, one day).

CREATE TABLE public_bins (
    id            UUID PRIMARY KEY,
    slug          VARCHAR(32) NOT NULL UNIQUE,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMP   NOT NULL,
    request_count BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_public_bins_expires_at ON public_bins (expires_at);

COMMENT ON TABLE public_bins IS 'Anonymous webhook tester URLs from the public site; deleted a day after creation.';

-- A sequence rather than a UUID, so "newest first" and "keep the latest hundred" have an order
-- that two requests in the same millisecond cannot tie on.
CREATE TABLE public_bin_requests (
    id             BIGSERIAL PRIMARY KEY,
    bin_id         UUID         NOT NULL REFERENCES public_bins (id) ON DELETE CASCADE,
    method         VARCHAR(10)  NOT NULL,
    query_string   TEXT,
    headers        TEXT,
    body           TEXT,
    body_truncated BOOLEAN      NOT NULL DEFAULT FALSE,
    size_bytes     BIGINT       NOT NULL,
    content_type   VARCHAR(255),
    source_ip      VARCHAR(45),
    received_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_public_bin_requests_bin ON public_bin_requests (bin_id, id DESC);

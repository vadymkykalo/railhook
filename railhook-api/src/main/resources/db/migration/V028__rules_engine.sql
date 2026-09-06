-- ============================================================
-- V028: Rules Engine — event routing, filtering, transformation rules
-- ============================================================

-- Rules: define conditions that match events
CREATE TABLE rules (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id            UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name                  VARCHAR(255) NOT NULL,
    description           TEXT,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    priority              INTEGER      NOT NULL DEFAULT 0,

    -- Fast prefilter: event type pattern (uses EventTypeMatcher syntax: exact, *, **)
    -- NULL = catch-all (matches every event type)
    event_type_pattern    VARCHAR(255),

    -- Structured conditions evaluated against event payload (JSONB array)
    -- Example: [{"field":"$.data.amount","operator":"gt","value":1000}]
    conditions            JSONB        NOT NULL DEFAULT '[]'::jsonb,

    -- Logical operator for combining conditions: AND (all must match) / OR (any must match)
    conditions_operator   VARCHAR(3)   NOT NULL DEFAULT 'AND',

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_rule_name_project UNIQUE (project_id, name),
    CONSTRAINT chk_conditions_operator CHECK (conditions_operator IN ('AND', 'OR'))
);

CREATE INDEX idx_rules_project_id ON rules(project_id);
CREATE INDEX idx_rules_project_enabled ON rules(project_id, enabled) WHERE enabled = TRUE;
CREATE INDEX idx_rules_event_type_pattern ON rules(project_id, event_type_pattern) WHERE enabled = TRUE;

-- Rule actions: what happens when a rule matches
CREATE TABLE rule_actions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id               UUID         NOT NULL REFERENCES rules(id) ON DELETE CASCADE,

    -- Action type: ROUTE (send to endpoint), TRANSFORM (apply transformation),
    --              DROP (discard, skip delivery), TAG (add metadata)
    type                  VARCHAR(50)  NOT NULL,

    -- For ROUTE action: which endpoint to deliver to
    endpoint_id           UUID         REFERENCES endpoints(id) ON DELETE CASCADE,

    -- For TRANSFORM action: which transformation to apply
    transformation_id     UUID         REFERENCES transformations(id) ON DELETE SET NULL,

    -- Generic config (JSON) for extensibility:
    --   ROUTE: {"customHeaders": {...}, "maxAttempts": 5, "timeoutSeconds": 30}
    --   TAG:   {"tags": ["high-value", "fraud-review"]}
    --   DROP:  {"reason": "Filtered by rule"}
    config                JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- Execution order within a rule
    sort_order            INTEGER      NOT NULL DEFAULT 0,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_action_type CHECK (type IN ('ROUTE', 'TRANSFORM', 'DROP', 'TAG'))
);

CREATE INDEX idx_rule_actions_rule_id ON rule_actions(rule_id);
CREATE INDEX idx_rule_actions_endpoint_id ON rule_actions(endpoint_id);

-- Track rule execution stats (lightweight, no FK to events for perf)
CREATE TABLE rule_execution_log (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id               UUID         NOT NULL,
    project_id            UUID         NOT NULL,
    event_id              UUID         NOT NULL,
    matched               BOOLEAN      NOT NULL,
    actions_executed      INTEGER      NOT NULL DEFAULT 0,
    evaluation_time_ms    INTEGER,
    executed_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partitioned index for fast cleanup and queries
CREATE INDEX idx_rule_exec_log_project_time ON rule_execution_log(project_id, executed_at DESC);
CREATE INDEX idx_rule_exec_log_rule_id ON rule_execution_log(rule_id, executed_at DESC);

-- Cleanup: auto-delete logs older than 7 days (handled by scheduled job)

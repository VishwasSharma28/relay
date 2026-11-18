CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL UNIQUE,
    key_hash CHAR(64) NOT NULL UNIQUE,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT fk_api_keys_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_keys_environment_tenant
        FOREIGN KEY (project_id, environment_id)
        REFERENCES environments(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_api_keys_created_by
        FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_api_keys_name_not_blank
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_api_keys_prefix_format
        CHECK (key_prefix ~ '^relay_(sandbox|dev|live)_[A-Za-z0-9_-]{1,14}$'),
    CONSTRAINT chk_api_keys_hash_format
        CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_api_keys_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX idx_api_keys_project_environment_active
    ON api_keys(project_id, environment_id, created_at DESC)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_api_keys_active_expiry
    ON api_keys(expires_at)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL;

-- =====================================================
-- V7: Custom APIs, module installations, releases,
--     deployments, quotas and usage counters
-- =====================================================

-- Custom APIs: declarative workflows with immutable versions.
CREATE TABLE custom_apis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_custom_apis_project_id UNIQUE (project_id, id),
    CONSTRAINT chk_custom_apis_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE UNIQUE INDEX uq_custom_apis_project_active_slug
    ON custom_apis(project_id, slug) WHERE deleted_at IS NULL;

CREATE TABLE custom_api_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    custom_api_id UUID NOT NULL,
    version INTEGER NOT NULL,
    http_method VARCHAR(6) NOT NULL,
    definition JSONB NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_custom_api_versions_api
        FOREIGN KEY (project_id, custom_api_id) REFERENCES custom_apis(project_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_custom_api_versions_version UNIQUE (custom_api_id, version),
    CONSTRAINT chk_custom_api_versions_positive CHECK (version > 0),
    CONSTRAINT chk_custom_api_versions_method CHECK (http_method IN ('GET', 'POST')),
    CONSTRAINT chk_custom_api_versions_definition CHECK (jsonb_typeof(definition) = 'object')
);

CREATE FUNCTION prevent_immutable_row_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$$;

-- Versions may only disappear through cascade deletes of their parent.
CREATE TRIGGER trg_custom_api_versions_immutable
    BEFORE UPDATE ON custom_api_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_immutable_row_update();

-- Module installations.
CREATE TABLE project_module_installations (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    module_key VARCHAR(64) NOT NULL,
    module_version VARCHAR(20) NOT NULL,
    installed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (project_id, module_key)
);

-- Releases: immutable snapshots.
CREATE TABLE releases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    notes TEXT,
    snapshot JSONB NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_releases_project_version UNIQUE (project_id, version),
    CONSTRAINT uq_releases_project_id UNIQUE (project_id, id),
    CONSTRAINT chk_releases_positive CHECK (version > 0),
    CONSTRAINT chk_releases_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);

CREATE TRIGGER trg_releases_immutable
    BEFORE UPDATE ON releases
    FOR EACH ROW EXECUTE FUNCTION prevent_immutable_row_update();

-- Deployments: append-only history.
CREATE TABLE deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    release_id UUID NOT NULL,
    action VARCHAR(10) NOT NULL,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_deployments_environment
        FOREIGN KEY (project_id, environment_id) REFERENCES environments(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_deployments_release
        FOREIGN KEY (project_id, release_id) REFERENCES releases(project_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_deployments_action CHECK (action IN ('DEPLOY', 'ROLLBACK'))
);

CREATE TRIGGER trg_deployments_immutable
    BEFORE UPDATE ON deployments
    FOR EACH ROW EXECUTE FUNCTION prevent_immutable_row_update();

CREATE INDEX idx_deployments_environment_created
    ON deployments(project_id, environment_id, created_at DESC, id DESC);

-- Quotas (row optional; defaults applied in code when absent).
CREATE TABLE project_quotas (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    daily_request_limit INTEGER NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_project_quotas_daily CHECK (daily_request_limit > 0),
    CONSTRAINT chk_project_quotas_minute CHECK (requests_per_minute > 0)
);

-- Usage counters. Daily rows are durable history; minute rows are a
-- short-lived rate-limit window pruned by the application.
CREATE TABLE usage_daily (
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    day DATE NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, environment_id, day),
    CONSTRAINT fk_usage_daily_environment
        FOREIGN KEY (project_id, environment_id) REFERENCES environments(project_id, id) ON DELETE CASCADE
);

CREATE TABLE usage_minute (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, window_start)
);

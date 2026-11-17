-- =====================================================
-- V2: Resources, immutable schema versions and records
-- =====================================================

CREATE TABLE resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    description TEXT,
    current_schema_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_resources_project
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT uq_resources_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_resources_project_slug UNIQUE (project_id, slug),
    CONSTRAINT chk_resources_slug
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE resource_schema_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    version INTEGER NOT NULL,
    schema_definition JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_resource_schema_versions_resource
        FOREIGN KEY (project_id, resource_id)
        REFERENCES resources(project_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_resource_schema_versions_version
        UNIQUE (resource_id, version),
    CONSTRAINT uq_resource_schema_versions_project_resource_id
        UNIQUE (project_id, resource_id, id),
    CONSTRAINT chk_resource_schema_versions_positive_version
        CHECK (version > 0),
    CONSTRAINT chk_resource_schema_versions_object
        CHECK (jsonb_typeof(schema_definition) = 'object')
);

-- The resource and schema version must refer to the same project/resource.
-- The deferred constraint permits creating a resource and its first schema in
-- one transaction before setting the current version pointer.
ALTER TABLE resources
    ADD CONSTRAINT fk_resources_current_schema_version
    FOREIGN KEY (project_id, id, current_schema_version_id)
    REFERENCES resource_schema_versions(project_id, resource_id, id)
    ON DELETE RESTRICT
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_records_environment
        FOREIGN KEY (project_id, environment_id)
        REFERENCES environments(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_records_resource
        FOREIGN KEY (project_id, resource_id)
        REFERENCES resources(project_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_records_data_object
        CHECK (jsonb_typeof(data) = 'object')
);

CREATE INDEX idx_resources_project_active_slug
    ON resources(project_id, slug)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_resource_schema_versions_resource_version
    ON resource_schema_versions(resource_id, version DESC);

CREATE INDEX idx_records_environment_resource_created
    ON records(project_id, environment_id, resource_id, created_at DESC, id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_records_data_gin
    ON records USING GIN (data jsonb_path_ops)
    WHERE deleted_at IS NULL;

-- Schema versions are append-only. A new schema must always receive a new row.
CREATE FUNCTION prevent_resource_schema_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource schema versions are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_resource_schema_versions_immutable
    BEFORE UPDATE OR DELETE ON resource_schema_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_resource_schema_version_mutation();

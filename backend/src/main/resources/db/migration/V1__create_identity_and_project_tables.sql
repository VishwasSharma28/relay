-- =====================================================
-- V1: Identity, Projects and Environments
-- =====================================================

-- 1. Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,

    full_name VARCHAR(150) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name VARCHAR(150) NOT NULL,
    slug VARCHAR(100) NOT NULL,

    description TEXT,

    created_by UUID NOT NULL REFERENCES users(id),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    
    CONSTRAINT uq_projects_slug UNIQUE (slug),
    CONSTRAINT chk_projects_slug
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- 3. Project Members
CREATE TABLE project_members (
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,

    role VARCHAR(30) NOT NULL DEFAULT 'MEMBER',

    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (project_id, user_id),

    CONSTRAINT fk_project_members_project
        FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE CASCADE,

    CONSTRAINT fk_project_members_user
        FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT chk_project_members_role
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

-- 4. Environments
CREATE TABLE environments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    project_id UUID NOT NULL,

    name VARCHAR(50) NOT NULL,
    slug VARCHAR(30) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_environments_project
        FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE CASCADE,

    CONSTRAINT uq_environments_project_slug
        UNIQUE (project_id, slug),

    CONSTRAINT uq_environments_project_id
        UNIQUE (project_id, id),

    CONSTRAINT chk_environments_slug
        CHECK (slug IN ('sandbox', 'development', 'production'))
);

-- Indexes
CREATE INDEX idx_projects_created_by
    ON projects(created_by);

CREATE INDEX idx_project_members_user
    ON project_members(user_id);

CREATE INDEX idx_environments_project
    ON environments(project_id);
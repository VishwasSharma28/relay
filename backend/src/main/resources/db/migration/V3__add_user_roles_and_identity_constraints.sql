ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

CREATE UNIQUE INDEX uq_users_email_normalized
    ON users (lower(email));

CREATE UNIQUE INDEX uq_project_members_single_owner
    ON project_members(project_id)
    WHERE role = 'OWNER';

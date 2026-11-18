INSERT INTO project_members (project_id, user_id, role)
SELECT p.id, p.created_by, 'OWNER'
FROM projects p
WHERE NOT EXISTS (
    SELECT 1 FROM project_members existing_owner
    WHERE existing_owner.project_id = p.id AND existing_owner.role = 'OWNER'
)
ON CONFLICT (project_id, user_id)
DO UPDATE SET role = 'OWNER';

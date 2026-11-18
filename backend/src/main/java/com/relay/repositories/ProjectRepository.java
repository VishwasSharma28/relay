package com.relay.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.relay.dto.EnvironmentResponse;
import com.relay.dto.ProjectMemberResponse;
import com.relay.dto.ProjectResponse;
import com.relay.models.ProjectMemberRole;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ProjectResponse create(UUID id, String name, String slug, String description, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO projects (id,name,slug,description,created_by)
                VALUES (?,?,?,?,?) RETURNING id,name,slug,description,created_by,created_at,updated_at
                """, PROJECT_MAPPER, id, name.trim(), slug, description, createdBy);
    }

    public void addOwner(UUID projectId, UUID userId) {
        jdbc.update("INSERT INTO project_members(project_id,user_id,role) VALUES (?,?,'OWNER')", projectId, userId);
    }

    public void createEnvironment(UUID projectId, String name, String slug) {
        jdbc.update("INSERT INTO environments(project_id,name,slug) VALUES (?,?,?)", projectId, name, slug);
    }

    public List<ProjectResponse> findAllForUser(UUID userId) {
        return jdbc.query("""
                SELECT p.id,p.name,p.slug,p.description,p.created_by,p.created_at,p.updated_at
                FROM projects p JOIN project_members m ON m.project_id=p.id
                WHERE m.user_id=? ORDER BY p.created_at,p.id
                """, PROJECT_MAPPER, userId);
    }

    public Optional<ProjectResponse> findForUser(UUID projectId, UUID userId) {
        return jdbc.query("""
                SELECT p.id,p.name,p.slug,p.description,p.created_by,p.created_at,p.updated_at
                FROM projects p JOIN project_members m ON m.project_id=p.id
                WHERE p.id=? AND m.user_id=?
                """, PROJECT_MAPPER, projectId, userId).stream().findFirst();
    }

    public Optional<ProjectMemberRole> findMemberRole(UUID projectId, UUID userId) {
        return jdbc.query("SELECT role FROM project_members WHERE project_id=? AND user_id=?",
                (rs, row) -> ProjectMemberRole.valueOf(rs.getString("role")), projectId, userId).stream().findFirst();
    }

    public void update(UUID projectId, String name, String slug, String description) {
        jdbc.update("""
                UPDATE projects SET name=COALESCE(?,name), slug=COALESCE(?,slug),
                    description=COALESCE(?,description), updated_at=NOW() WHERE id=?
                """, name, slug, description, projectId);
    }

    public void delete(UUID projectId) { jdbc.update("DELETE FROM projects WHERE id=?", projectId); }

    public List<ProjectMemberResponse> findMembers(UUID projectId) {
        return jdbc.query("""
                SELECT u.id AS user_id,u.email,u.full_name,m.role,m.joined_at
                FROM project_members m JOIN users u ON u.id=m.user_id
                WHERE m.project_id=? ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,
                    u.email
                """, MEMBER_MAPPER, projectId);
    }

    public void addMember(UUID projectId, UUID userId, ProjectMemberRole role) {
        jdbc.update("INSERT INTO project_members(project_id,user_id,role) VALUES (?,?,?)",
                projectId, userId, role.name());
    }

    public void updateMemberRole(UUID projectId, UUID userId, ProjectMemberRole role) {
        jdbc.update("UPDATE project_members SET role=? WHERE project_id=? AND user_id=?",
                role.name(), projectId, userId);
    }

    public void removeMember(UUID projectId, UUID userId) {
        jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?", projectId, userId);
    }

    public List<EnvironmentResponse> findEnvironments(UUID projectId) {
        return jdbc.query("""
                SELECT id,project_id,name,slug,created_at FROM environments
                WHERE project_id=? ORDER BY CASE slug WHEN 'sandbox' THEN 0 WHEN 'development' THEN 1 ELSE 2 END
                """, ENVIRONMENT_MAPPER, projectId);
    }

    private static final RowMapper<ProjectResponse> PROJECT_MAPPER = ProjectRepository::mapProject;
    private static ProjectResponse mapProject(ResultSet rs, int row) throws SQLException {
        return new ProjectResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private static final RowMapper<ProjectMemberResponse> MEMBER_MAPPER = (rs, row) ->
            new ProjectMemberResponse(rs.getObject("user_id", UUID.class), rs.getString("email"),
                    rs.getString("full_name"), ProjectMemberRole.valueOf(rs.getString("role")),
                    rs.getObject("joined_at", java.time.OffsetDateTime.class));

    private static final RowMapper<EnvironmentResponse> ENVIRONMENT_MAPPER = (rs, row) ->
            new EnvironmentResponse(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getString("name"), rs.getString("slug"),
                    rs.getObject("created_at", java.time.OffsetDateTime.class));
}

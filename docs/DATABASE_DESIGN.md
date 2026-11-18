# Relay â€” Database Design Specification

**Version:** 1.0
**Status:** Approved conceptual architecture
**Database:** PostgreSQL
**Database access:** JDBC
**Schema management:** Flyway
**Architecture:** Multi-tenant, modular monolith

---

## 1. Overview

Relay is an API-as-a-Service platform that enables developers to create, configure, test and publish backend services without building an entire backend from scratch.

The database must support:

- Multiple users and projects.
- Multiple isolated environments per project.
- Dynamically defined resources and schemas.
- Persistent JSONB application records.
- Automatically generated CRUD APIs.
- Developer-defined custom APIs.
- Reusable platform feature modules.
- Versioned releases and deployments.
- Project-level and environment-level access control.
- Data export and backend handoff.

## 2. Architectural principles

### 2.1 Project as the tenant boundary

Every project represents an independent application.

All project-owned entities must be associated with a project.

A user must never be able to access another project's resources or records without explicit authorization.

### 2.2 Environment isolation

Each project can contain multiple environments:

- Sandbox
- Development
- Production

Application records must be isolated by environment.

The same resource definition can be used across environments, while the underlying application records remain separate.

### 2.3 Hybrid database model

Relay uses relational tables for platform configuration and JSONB for dynamically structured application data.

Relational storage:
- Users
- Projects
- Environments
- Resources
- API definitions
- API versions
- Feature modules
- Releases
- Deployments

JSONB storage:
- Resource schemas
- Application records
- API request and response schemas
- Workflow definitions
- Feature configurations
- Audit metadata

### 2.4 JDBC-first implementation

All database operations will use JDBC.

Preferred Spring components:

- JdbcTemplate
- NamedParameterJdbcTemplate
- DataSource
- Spring transaction management

No Hibernate or Spring Data JPA.

SQL queries, constraints and migrations will be explicitly maintained.

## 3. Database domains

### 3.1 Identity and project management

**users**

Stores user identity and authentication information.

**projects**

Represents an application created by a user.

**project_members**

Manages project membership and role-based access.

### 3.2 Environment management

**environments**

Represents isolated execution and data environments within a project.

Each environment belongs to exactly one project.

### 3.3 Resource and schema management

**resources**

Stores user-defined resource metadata.

**resource_schema_versions**

Stores historical versions of resource schemas.

Schema definitions are stored as JSONB.

The resource maintains a reference to its current schema version.

### 3.4 Dynamic application data

**records**

Stores application records belonging to user-defined resources.

Each record is associated with:

- A project.
- An environment.
- A resource.

The actual user-defined fields are stored in JSONB.

### 3.5 Custom API management

**api_definitions**

Stores custom endpoint metadata, including HTTP method, route and implementation type.

**api_versions**

Stores versioned API contracts and implementation references.

Supported implementation types:

- Workflow-based execution.
- Java-based execution.

Java implementation artifacts will be referenced through artifact metadata rather than storing executable binaries directly in PostgreSQL.

### 3.6 Platform feature management

**feature_catalog**

Stores globally available Relay platform modules.

**feature_versions**

Stores versioned feature implementations and configuration schemas.

**project_features**

Stores the modules enabled by individual projects.

### 3.7 Release management

**releases**

Stores immutable snapshots of project backend configurations.

**release_resources**

Associates resource schema versions with releases.

**release_apis**

Associates custom API versions with releases.

**release_features**

Associates feature versions and configuration snapshots with releases.

**deployments**

Tracks deployments of releases into environments, including status and execution timestamps.

### 3.8 Security and audit

**api_keys**

Stores environment-scoped API key metadata, a non-secret lookup prefix, and a SHA-256 hash of each random secret. The raw secret is returned only in the creation response and is never stored. Each row references its project and environment using a composite tenant foreign key.

**audit_logs**

Tracks important project and administrative actions.

## 4. Data integrity requirements

The database must enforce:

1. Unique user email addresses.
2. Unique resource slugs within projects.
3. Unique environment slugs within projects.
4. Unique schema versions within resources.
5. Unique API versions within API definitions.
6. Unique feature versions within feature definitions.
7. Unique release versions within projects.
8. Unique feature installations within projects.
9. Valid project, environment and resource relationships.
10. Valid release, API version and feature version relationships.

Composite foreign keys must enforce tenant consistency.

For example, a record's resource and environment must belong to the same project as the record itself.

## 5. Indexing strategy

Initial indexes will cover:

- Project ownership.
- Project membership.
- Resource lookup by project and slug.
- Environment lookup by project and slug.
- Records lookup by project, environment and resource.
- Record creation timestamps for pagination.
- JSONB record queries where required.
- API route lookup.
- Deployment lookup by environment.
- API key hash lookup.

Indexes will be reviewed against actual query patterns.

## 6. Transaction requirements

Operations involving multiple related database changes must be transactional.

Examples:

- Creating a resource and its first schema version.
- Creating a release and its component snapshots.
- Publishing a release to an environment.
- Installing or updating a project feature.

A failed operation must not leave partially updated configuration.

## 7. Release and deployment requirements

Releases are immutable after creation.

A deployment associates a release with an environment.

Multiple historical deployments may exist for an environment, but only one deployment may have ACTIVE status at a time.

Rollback should be possible by deploying an earlier release.

Deployment state changes must be transactional and concurrency-safe.

## 8. Migration strategy

Flyway will manage database schema changes.

Migrations will be sequential and version-controlled.

Migration history in this repository:

1. V1: identity, projects, memberships, and environments.
2. V2: resources, immutable schema versions, and JSONB records.
3. V3: user roles and identity constraints.
4. V4: project creator ownership backfill.
5. V5: record schema-version references and optimistic record versions.
6. V6: environment-scoped API keys.
7. V7: custom API definitions and immutable versions, module installations, immutable releases and deployment history, project quotas, and daily/minute usage counters.

V7 stores release contents as a JSONB snapshot on `releases`; it does not create separate `release_resources` or `release_apis` tables. The module catalog is shipped as `backend/src/main/resources/modules/catalog.json`, and `project_module_installations` records project installs. The platform-feature tables described above remain design concepts and are not created by V7.

Future schema changes must use new migrations; applied migrations are not edited.

## 9. Out of scope for v1

The following are intentionally deferred:

- Billing and subscriptions.
- Advanced usage metering.
- Distributed execution infrastructure.
- Arbitrary Java code execution.
- Real-time subscriptions.
- Database sharding.
- Multi-region deployments.

## 10. Implementation philosophy

Relay will initially operate as a modular Spring Boot application backed by PostgreSQL.

The architecture should support future scaling without introducing distributed infrastructure prematurely.

Database integrity, tenant isolation, API compatibility and reliable migrations take priority over premature optimization.

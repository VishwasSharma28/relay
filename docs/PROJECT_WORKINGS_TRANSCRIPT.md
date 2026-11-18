# Relay Project Workings Transcript

This transcript describes the project as it exists in the repository today and the intended flow described by its design documents. The backend implements session-based identity and project management, project-scoped resources and JSONB records, and environment-scoped API key management and authentication using Spring JDBC and Flyway.

## 1. Starting the local project

**Developer:** “How do I bring Relay up locally?”

**Project:** “Start PostgreSQL with Docker Compose. Compose reads `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, and `DB_PORT`, creates the `relay` database using those values, binds the configured port to localhost, and stores database files in the persistent `relay_pgdata` volume. Its health check waits for PostgreSQL to accept connections.”

“Then start the backend with Java 21 and Maven. Spring Boot launches `RelayBackendApplication`, serves on port 8080, creates a JDBC connection pool, and runs Flyway migrations from `classpath:db/migration`.”

**Configuration detail:** `application.properties` currently connects to `localhost:5434`, while `.env.example` sets `DB_PORT=5432`. The local database port and JDBC URL need to be aligned for the example configuration to connect successfully.

## 2. What happens during database setup

**Flyway, migration V1:** “I create the identity and project foundation.”

- `users` stores email, password hash, name, and timestamps.
- `projects` belongs to a creator and has a unique, lowercase hyphenated slug.
- `project_members` connects users to projects with `OWNER`, `ADMIN`, or `MEMBER` roles.
- `environments` gives each project its own `sandbox`, `development`, and `production` environments.
- Foreign keys, uniqueness rules, and indexes enforce the basic relationships and lookup paths.

**Flyway, migration V2:** “I add configurable resources and their records.”

- `resources` belongs to a project and has a unique project-local slug. A resource can be soft-deleted and points to its current schema version.
- `resource_schema_versions` stores JSON Schema documents as JSONB. Versions are append-only: a database trigger rejects updates and deletes.
- `records` stores each application's dynamic fields as JSONB and associates every row with a project, environment, and resource.
- Composite foreign keys ensure that a record's environment and resource belong to the same project. A database check requires record data to be a JSON object.
- Indexes support active resource lookup, schema history lookup, environment/resource record scans, and JSONB queries.

The schema deliberately keeps platform configuration relational and user-defined record fields in JSONB. All database interaction is intended to use JDBC; Hibernate and Spring Data JPA are excluded by the architecture decisions.

## 3. Intended generated-record request flow

The generated record flow is implemented at `/api/v1` and on the existing project/environment record routes.

**Client:** “I send a request to `/api/v1/projects/{projectId}/resources/{resourceSlug}` with an environment-specific bearer API key.”

**Relay, intended behavior:** “I resolve the key to its environment, confirm the route project matches the key's project, locate the active resource, and determine the schema version effective for that environment. A project mismatch is hidden behind a `404`; a missing, invalid, or revoked key receives `401`.”

For a create request, Relay is intended to validate the submitted JSON object against the effective schema, then store it with a generated record ID, timestamps, environment/resource/project references, and the schema version used for validation. The contract returns `201`, the record representation, an `ETag`, and a `Location` header.

For a list or read request, Relay is intended to return active records only. Lists support bounded page sizes, schema-declared top-level scalar filters, deterministic sorting, and opaque keyset pagination. Individual records include server-managed metadata and an ETag.

For a patch request, Relay is intended to require `application/merge-patch+json` and `If-Match`. It merges the patch into the stored object, validates the complete result against the effective schema, then atomically updates the JSON, schema version, timestamp, and record version. A stale ETag receives `412`; a missing ETag receives `428`. An empty patch is a no-op.

For a delete request, Relay is intended to require `If-Match`, atomically mark the record deleted, increment its record version, and return `204` with the tombstone ETag. Deleted records are not addressable through the generated routes.

These writes are designed to be tenant-scoped and concurrency-safe. Existing records retain the schema version under which they were last validated; schema publication does not silently rewrite record data.

## 4. Environments and releases

Each project has isolated Sandbox, Development, and Production environments, and application records are scoped to an environment. The architecture plans for immutable releases that capture versioned backend configuration and deployments that associate releases with environments. Historical deployments are retained so a previous release can be redeployed.

V6 creates environment-scoped API keys. Releases, deployments, custom API definitions, and feature-module tables remain architectural plans, not working flows in the current backend.

## 5. Current implementation boundary

The repository currently provides:

- A Spring Boot application entry point and JDBC, security, validation, web, PostgreSQL, and Flyway dependencies.
- Docker Compose configuration for local PostgreSQL with a persistent volume.
- Flyway V1 through V6 migrations, including resource schema compatibility and environment-scoped API keys.
- Session-based authentication, project membership management, resource and schema endpoints, and JSONB record CRUD.
- API key create/list/detail/revoke endpoints and bearer authentication for generated record APIs.
- Integration tests for identity, projects, resources, records, API key lifecycle, permissions, tenant isolation, and migrations.
- Design documentation for the database, architecture decisions, generated-record API, and local running/testing.

Release/deployment workflows, custom API execution, and feature-module management are not implemented yet.

## 6. Design intent

Relay is being built as a backend-first modular monolith. Its core boundary is the project tenant; environments isolate data within that tenant; resource schemas describe dynamic records; and immutable versions are intended to make changes and deployments reviewable. The initial system favors database constraints, explicit SQL, and transactional operations before adding distributed execution, billing, arbitrary Java execution, or multi-region infrastructure.

## Repository references

- [README](../README.md)
- [Architecture decisions](ARCHITECTURE_DECISIONS.md)
- [Database design](DATABASE_DESIGN.md)
- [Generated record API contract](RESOURCE_RECORD_API_CONTRACT.md)
- [V1 identity and project migration](../backend/src/main/resources/db/migration/V1__create_identity_and_project_tables.sql)
- [V2 resources and records migration](../backend/src/main/resources/db/migration/V2__create_resources_and_records.sql)

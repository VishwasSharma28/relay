# Relay — Architecture Decision Records

Version: 1.0
Status: Initial decisions

## ADR-001: Backend Technology

Decision:
- Java 21
- Spring Boot 3
- Spring MVC
- Spring Security

## ADR-002: Database

Decision:
- PostgreSQL 17
- JDBC for all database interactions
- No Hibernate or Spring Data JPA
- Flyway for database migrations

## ADR-003: Database Infrastructure

Decision:
- PostgreSQL runs through Docker Compose.
- Persistent named Docker volume.
- Every teammate maintains their own local environment.

## ADR-004: Project Identification

Decision:
- Every project has a UUID.
- UUID is used for project identification in API routes.
- Resource slugs identify resources within projects.

## ADR-005: Environment Isolation

Decision:
- Projects support Sandbox, Development and Production environments.
- Application records are isolated between environments.
- Environment-specific API keys.

## ADR-006: Custom APIs

Decision:
- Support workflow-based API implementations.
- Support Java-based API implementations.
- Java execution must eventually be isolated from the main application.

## ADR-007: Platform Features

Decision:
- Relay administrators publish reusable feature modules.
- Projects can independently enable platform modules.
- Feature implementations are versioned.

## ADR-008: Release Management

Decision:
- Releases contain versioned backend configuration.
- Releases are immutable.
- Deployments associate releases with environments.
- Historical deployments are retained for rollback.

## ADR-009: Development Strategy

Decision:
- Backend first.
- Frontend after backend functionality is established.
- Build quickly while understanding and reviewing architectural decisions.
- Modular monolith for the initial implementation.

## ADR-010: Deferred Infrastructure

Deferred:
- Billing and subscriptions.
- Distributed execution.
- Arbitrary Java code execution.
- Database sharding.
- Multi-region infrastructure.

## ADR-011: Environment-Scoped API Keys

Decision:
- API keys are bound to exactly one project and one project environment.
- Management operations use the existing authenticated session API. Only project OWNER or ADMIN members may create or revoke keys; project members may view key metadata.
- The complete secret is generated from 256 bits of `SecureRandom` entropy, shown only in the creation response, and never persisted. PostgreSQL stores a non-secret lookup prefix and SHA-256 digest; authentication compares digests using `MessageDigest.isEqual`.
- Bearer API keys authenticate generated record and custom-function routes only. They do not represent a user and cannot authenticate management endpoints.

## ADR-012: Declarative Custom APIs and Release Snapshots

- Custom APIs are JSON workflow definitions interpreted by the backend; user-supplied code is never executed.
- Workflow record operations inherit the authenticated project's environment context.
- Resource schema versions and custom API versions are captured in immutable project releases.
- Deployments select a release snapshot per environment, keeping sandbox behavior independent from deployed development and production environments.
- Usage counters and quota checks apply to API-key-authenticated requests.
- `/api/v1/projects/{projectId}/resources/{resourceSlug}` derives the environment from the key. The existing session route with an explicit environment slug also accepts a key only when both project and environment match.
- CSRF remains enabled for session-authenticated management and record requests. It is not required for the bearer-only `/api/v1` API, and is conditionally skipped for bearer-authenticated requests to existing record routes.

## Pending Architectural Decisions

- Shared JSONB records table versus dynamically generated tables.
- Final custom Java execution mechanism.
- Sandbox and production resource quotas.
- Project slug uniqueness strategy.
- Deployment concurrency implementation.

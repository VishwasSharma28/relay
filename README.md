# Relay

Relay is an API-as-a-Service platform. You define resources with immutable, versioned schemas, and Relay gives you:
- a JSONB-backed CRUD API for each one, per environment (sandbox, development, production)
- environment-scoped API keys
- declarative custom API workflows
- reusable modules
- immutable releases with deployment history and rollback
- quotas and rate limiting
- a full JSON and OpenAPI export for backend handoff

| Layer | Stack |
|---|---|
| Backend | Java 21, Spring Boot 4.1.2, Spring JDBC (no JPA), Flyway, PostgreSQL |
| Frontend | Vite, React 18, TypeScript (`frontend/`), bundled into the backend jar |
| Packaging | Multi-stage `Dockerfile`, `docker-compose.yml`, GitHub Actions CI |

## Quick start (local development, PowerShell)
```powershell
# Start the existing local PostgreSQL service.
docker compose up -d postgres

# In one terminal, configure DB_USERNAME/DB_PASSWORD as described in
# docs/RUNNING_AND_TESTING.md, then start the backend.
Push-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```
In a second terminal:
```powershell
Push-Location frontend
npm.cmd ci
npm.cmd run dev
```
Open http://localhost:5173 and register an account.

### Run everything in Docker
```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# Edit .env and set a strong DB_PASSWORD before starting the stack.
docker compose up --build
```
Open http://localhost:8080.

The compose file preserves the local PostgreSQL service and `relay_pgdata` volume. Its app container connects over the internal `postgres` service network. To bootstrap an initial administrator, set `RELAY_ADMIN_EMAIL`, `RELAY_ADMIN_PASSWORD`, and `RELAY_ADMIN_FULL_NAME` in `.env`; leave them blank to disable bootstrap.

## Tests
```powershell
Push-Location backend
.\mvnw.cmd clean verify
Pop-Location

Push-Location frontend
npm.cmd ci
npm.cmd run lint
npm.cmd test
npm.cmd run build
Pop-Location
```

## Documentation
- [docs/API_CONTRACT.md](docs/API_CONTRACT.md): every endpoint and payload, plus the workflow language
- [docs/OPERATIONS.md](docs/OPERATIONS.md): configuration, provisioning, migrations, deployment, backups and rollback
- [docs/MASTER_COMPLETION_REPORT.md](docs/MASTER_COMPLETION_REPORT.md): implementation status, test results, security review and known limitations
- [frontend/README.md](frontend/README.md): frontend structure

## Security model (summary)
- **Console:** session cookies (HttpOnly, SameSite=Lax, Secure in prod) with CSRF tokens.
- **Public API:** `Authorization: Bearer relay_<env>_...` keys. Only a SHA-256 hash is stored, and keys can be revoked or set to expire.
- **Isolation:** every query is scoped by project and environment. Resources outside your projects return 404.
- **Custom APIs:** these are interpreted JSON workflows. User-supplied code is never executed.

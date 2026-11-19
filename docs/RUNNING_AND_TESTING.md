# Running and Testing Relay Locally

This guide covers the implemented Relay API: health checks, session-based user authentication, project membership and access controls, project environments, dynamic resources and immutable schemas, JSONB records, environment-scoped API keys, declarative custom APIs, modules, releases, deployments, quotas, export, Flyway migrations, and automated tests. See [API_CONTRACT.md](API_CONTRACT.md) for the platform endpoints added in V7.

Commands below are for PowerShell, run from the repository root unless noted.

## Commands

1.

```powershell
docker --version
docker compose version
java -version
```

2.

```powershell
docker compose up -d --wait
docker compose ps
```

3.

```powershell
$env:DB_USERNAME = Read-Host "DB_USERNAME from .env"
$securePassword = Read-Host "DB_PASSWORD from .env" -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new("", $securePassword).Password
```

4.

```powershell
Push-Location backend
try {
    .\mvnw.cmd clean verify
}
finally {
    Pop-Location
}
```

5.

```powershell
Push-Location backend
try {
    .\mvnw.cmd spring-boot:run
}
finally {
    Pop-Location
}
```

6. Check the public health endpoint:

```powershell
curl.exe --max-time 5 -sS http://localhost:8080/api/health
```

7.

```powershell
docker compose exec postgres psql -U relay_user -d relay -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

8.

```powershell
docker compose down
```

## 1. Prerequisites

- Docker Desktop with Docker Compose available as `docker compose`.
- Java 21.
- The Maven wrapper included in `backend` (Maven itself is not required).

Check the installed tools:

```powershell
docker --version
docker compose version
java -version
```

## 2. Configure the database

Create a local `.env` from `.env.example` if you have not already, and set a local database password. `.env` is ignored by Git; keep credentials there and do not commit them.

There is currently a port mismatch to resolve before starting:

- `backend/src/main/resources/application.properties` connects to `localhost:5434`.
- `.env` supplies `DB_PORT` to Docker Compose; the checked-in example uses `5432`.

Set `DB_PORT=5434` in your local `.env` so Compose exposes PostgreSQL on the port the backend expects. The database name in `.env` should be `relay` unless you also change the JDBC URL in `application.properties`.

Start PostgreSQL and wait for its health check:

```powershell
docker compose up -d --wait
docker compose ps
```

The `postgres` service should show as running/healthy. If it does not, inspect its logs:

```powershell
docker compose logs postgres
```

## 3. Make database credentials available to Spring

Docker Compose reads `.env` automatically. A Java process launched from PowerShell does not, while Spring expects `DB_USERNAME` and `DB_PASSWORD` as process environment variables. Set them for the current PowerShell window. The password prompt does not display the value:

```powershell
$env:DB_USERNAME = Read-Host "DB_USERNAME from .env"
$securePassword = Read-Host "DB_PASSWORD from .env" -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new("", $securePassword).Password
```

These variables remain set only in this PowerShell process. Repeat this step in any separate terminal used to launch or test the backend.

## 4. Run the automated checks

From the repository root:

```powershell
Push-Location backend
try {
    .\mvnw.cmd test
}
finally {
    Pop-Location
}
```

The Spring tests need a reachable PostgreSQL database because application startup runs Flyway migrations. A successful run ends with `BUILD SUCCESS`. Tests cover health, authentication, project roles, resource/schema and record behavior, API key lifecycle, environment/project isolation, and migration constraints.

If Maven reports that it cannot connect to PostgreSQL, confirm that the container is healthy, `DB_PORT` is `5434`, and the current PowerShell window has the correct username and password variables.

## 5. Start the backend

With PostgreSQL running and the credentials set in this PowerShell window:

```powershell
Push-Location backend
try {
    .\mvnw.cmd spring-boot:run
}
finally {
    Pop-Location
}
```

Wait for a log line indicating `Started RelayBackendApplication`. On first startup, Flyway should apply the available migrations (`V1` through `V7`). Keep this window open while the backend is running; press `Ctrl+C` to stop it.

`GET /api/health` is publicly accessible and reports application and database status. Application APIs use JSON response envelopes. Authentication uses an HTTP session and CSRF protection.

## 6. Bootstrap the initial administrator

Set all three variables in the backend process environment before starting the application. Use a strong password of at least 12 characters and no more than 72 UTF-8 bytes; keep it in your secret manager or a local ignored `.env` file and do not commit it.

```powershell
$env:RELAY_ADMIN_EMAIL = Read-Host "Initial admin email"
$env:RELAY_ADMIN_FULL_NAME = Read-Host "Initial admin full name"
$secureAdminPassword = Read-Host "Initial admin password" -AsSecureString
$env:RELAY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new("", $secureAdminPassword).Password
```

When all values are configured, startup creates the account with an encoded password or promotes the matching existing account to `ADMIN`. Repeating startup is safe: it leaves an existing account's password and name unchanged while ensuring its role is `ADMIN`. The password is never logged. If any bootstrap variable is set, all three must be valid. Keep these variables unset after the initial bootstrap if they are not needed at later starts.

For browser or API clients, first call `GET /api/auth/csrf`; send the returned token in the `X-CSRF-TOKEN` header and retain the `XSRF-TOKEN` and `JSESSIONID` cookies for state-changing requests. Registration and login do not accept a role. The bootstrap account receives its role from the server environment.

## 7. Dynamic resources and records

Every endpoint below requires a logged-in session. Keep the same `WebRequestSession` for login, CSRF retrieval, and subsequent calls. Send the CSRF token in `X-CSRF-TOKEN` for every `POST`, `PATCH`, and `DELETE`. Resource creation, schema changes, metadata updates, and resource deletion require project OWNER or ADMIN membership. Project members can read resources and access records in a project environment.

Start an authenticated PowerShell session without placing credentials in the script:

```powershell
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrfResponse = Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/auth/csrf" -WebSession $session
$csrfToken = $csrfResponse.data.token
$email = Read-Host "Account email"
$securePassword = Read-Host "Account password" -AsSecureString
$password = [System.Net.NetworkCredential]::new("", $securePassword).Password
$loginBody = @{ email = $email; password = $password } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/auth/login" -WebSession $session `
    -Headers @{ "X-CSRF-TOKEN" = $csrfToken } -ContentType "application/json" -Body $loginBody
$password = $null
```

The same session and token can then be used for resource and record requests below. Retrieve a fresh token from `GET /api/auth/csrf` if the session is replaced or the token is rejected.

### Resource and schema endpoints

| Method and path | Behavior |
| --- | --- |
| `POST /api/projects/{projectId}/resources` | Create a resource and its initial schema version (version 1). |
| `GET /api/projects/{projectId}/resources` | List active project resources. |
| `GET /api/projects/{projectId}/resources/{resourceSlug}` | Get resource metadata and current schema version. |
| `PATCH /api/projects/{projectId}/resources/{resourceSlug}` | Update one or more of `name`, `slug`, and `description`. |
| `DELETE /api/projects/{projectId}/resources/{resourceSlug}` | Soft-delete the resource, retaining its schemas and records. |
| `POST /api/projects/{projectId}/resources/{resourceSlug}/schema-versions` | Publish a new immutable current schema version. |
| `GET /api/projects/{projectId}/resources/{resourceSlug}/schema-versions` | List the resource's schema history. |

Schema definitions use an object with a `properties` map. Each property has a `type` from `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME`, or `JSON`. `required` and `unique` are optional booleans, and `default` must match its field type. Unknown schema keys, unsupported types, malformed defaults, and invalid field names are rejected.

```powershell
$projectId = "00000000-0000-0000-0000-000000000000"
$resourceBody = @{
    name = "Tasks"
    slug = "tasks"
    description = "Tasks tracked by this project"
    schemaDefinition = @{
        type = "object"
        properties = @{
            title = @{ type = "STRING"; required = $true; unique = $true }
            done = @{ type = "BOOLEAN"; default = $false }
            priority = @{ type = "INTEGER"; default = 0 }
        }
    }
} | ConvertTo-Json -Depth 12

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/projects/$projectId/resources" `
    -WebSession $session -Headers @{ "X-CSRF-TOKEN" = $csrfToken } `
    -ContentType "application/json" -Body $resourceBody
```

To publish schema version 2 or later, send `POST` to the `schema-versions` endpoint with `{ "schemaDefinition": { ... } }`. Existing schema versions are immutable; records retain the schema version they were written against.

### Record endpoints

| Method and path | Behavior |
| --- | --- |
| `POST /api/projects/{projectId}/environments/{environmentSlug}/resources/{resourceSlug}/records` | Validate against the current schema and create a JSONB record. |
| `GET /api/projects/{projectId}/environments/{environmentSlug}/resources/{resourceSlug}/records` | List records with cursor pagination and optional scalar filters. |
| `GET /api/projects/{projectId}/environments/{environmentSlug}/resources/{resourceSlug}/records/{recordId}` | Get one active record. |
| `PATCH /api/projects/{projectId}/environments/{environmentSlug}/resources/{resourceSlug}/records/{recordId}` | Merge patch a record using `application/merge-patch+json` and its `If-Match` ETag. |
| `DELETE /api/projects/{projectId}/environments/{environmentSlug}/resources/{resourceSlug}/records/{recordId}` | Soft-delete a record using its `If-Match` ETag. |

The project, environment, and resource in the path are checked together on every request. Records in `sandbox`, `development`, and `production` are isolated. Create and update bodies are JSON objects; undeclared fields and values with the wrong type are rejected. The create response includes the record ETag. A patch or delete requires that value in `If-Match`; missing or stale values return `428` or `412`. Record lists accept `limit` (1â€“100), `sort` (`createdAt`, `updatedAt`, or `id`), `order` (`asc` or `desc`), and `cursor`. Filter declared scalar fields with query parameters such as `filter[done]=true` or `filter[title]="Review"`. A cursor is valid only for the same list filters and sorting.

```powershell
$recordsUrl = "http://localhost:8080/api/projects/$projectId/environments/sandbox/resources/tasks/records"
$newRecord = @{ title = "Review migration" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri $recordsUrl -WebSession $session `
    -Headers @{ "X-CSRF-TOKEN" = $csrfToken } -ContentType "application/json" -Body $newRecord
$recordId = $created.data.id
$recordEtag = $created.data.etag

Invoke-RestMethod -Method Get -Uri "$recordsUrl`?limit=25&filter[done]=false" -WebSession $session

$patchBody = '{"done":true}'
Invoke-RestMethod -Method Patch -Uri "$recordsUrl/$recordId" -WebSession $session `
    -Headers @{ "X-CSRF-TOKEN" = $csrfToken; "If-Match" = $recordEtag } `
    -ContentType "application/merge-patch+json" -Body $patchBody
```

## 8. Manage and use API keys

API key management uses the authenticated session and CSRF token. Only project OWNER or ADMIN members can create or revoke keys; project members can list and inspect metadata. The complete key secret appears only in the successful creation response. Store it securely then; Relay cannot show it again. Keys are scoped to one project environment. Sandbox keys start with `relay_sandbox_`, development keys with `relay_dev_`, and production keys with `relay_live_`.

| Method and path | Behavior |
| --- | --- |
| `POST /api/projects/{projectId}/api-keys` | Create a key using `name`, `environmentSlug`, and optional future `expiresAt`. |
| `GET /api/projects/{projectId}/api-keys` | List key metadata without secrets or hashes. |
| `GET /api/projects/{projectId}/api-keys/{keyId}` | Get key metadata. |
| `DELETE /api/projects/{projectId}/api-keys/{keyId}` | Revoke a key. |

```powershell
$keyBody = @{ name = "Contacts test"; environmentSlug = "sandbox" } | ConvertTo-Json
$createdKey = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8080/api/projects/$projectId/api-keys" -WebSession $session `
    -Headers @{ "X-CSRF-TOKEN" = $csrfToken } -ContentType "application/json" -Body $keyBody
$apiKey = $createdKey.data.secret  # Save securely; this is the only response containing the secret.

# The documented generated API derives environment from the key.
Invoke-RestMethod -Method Get `
    -Uri "http://localhost:8080/api/v1/projects/$projectId/resources/contacts" `
    -Headers @{ Authorization = "Bearer $apiKey" }

# The existing record route also accepts bearer keys and checks its explicit environment slug.
Invoke-RestMethod -Method Get `
    -Uri "http://localhost:8080/api/projects/$projectId/environments/sandbox/resources/contacts/records" `
    -Headers @{ Authorization = "Bearer $apiKey" }
```

API keys cannot authenticate user, project, membership, or API-key management endpoints. A project or environment mismatch is returned as `404`; missing, invalid, expired, or revoked keys return `401`. Session-based dashboard calls continue to use cookies and CSRF.

## 9. Check migration status directly

The migration test suite is the main verification of schema constraints. You can also inspect Flyway's history from PostgreSQL. With the default example database and user values:

```powershell
docker compose exec postgres psql -U relay_user -d relay -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

If you customized `DB_USERNAME` or `DB_NAME`, substitute those local values in the command. Successful migrations should appear with `success = t`.

## 10. Stop the local database

Stop the PostgreSQL container while keeping its data volume:

```powershell
docker compose down
```

To start again, use `docker compose up -d --wait`. The named volume preserves the database between runs. Removing that volume deletes local database data; do that only when you intentionally want a clean database reset.

## What a successful check confirms

- Docker can start PostgreSQL and the database accepts connections.
- Flyway can create the current V1–V7 schema.
- Spring Boot can initialize against that schema.
- The database enforces the migration invariants covered by the tests.

The automated suite also covers registration and password hashing, session login/logout, project CRUD, role-based membership permissions, tenant isolation, automatic sandbox/development/production environments, dynamic resource and schema version management, record validation and CRUD, ETags, pagination, environment separation, transaction rollback, API key lifecycle, secret hashing, last-used tracking, API-key isolation, workflow validation and execution, modules, release pinning and rollback, usage quotas, and export. Frontend checks are documented in the repository README and frontend package scripts.

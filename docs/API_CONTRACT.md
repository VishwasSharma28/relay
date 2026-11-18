# Relay API contract

All console endpoints use session-cookie auth (`/api/auth/login`) and the
envelope `{ "success": bool, "data": ..., "error": { "code", "message", "details" } }`.
Mutating console requests need the CSRF token: `GET /api/auth/csrf` sets the
`XSRF-TOKEN` cookie; send its value in the `X-CSRF-TOKEN` header.

Roles: any project member can read; `OWNER`/`ADMIN` ("managers") can change
resources, API keys, custom APIs, releases, deployments, modules and quotas.
Resources outside the caller's projects return `404`.

## Existing (V1–V6)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | public |
| GET | `/api/auth/csrf` | public |
| POST | `/api/auth/register` | `{email, password, fullName}` |
| POST | `/api/auth/login` | `{email, password}` |
| POST | `/api/auth/logout` | |
| GET | `/api/auth/me` | `{id, email, fullName, role}` |
| GET/POST | `/api/projects` | create `{name, slug, description}`; sandbox, development, production environments are created automatically |
| GET/PATCH/DELETE | `/api/projects/{projectId}` | |
| GET/POST | `/api/projects/{projectId}/members` | add `{email, role}` |
| PATCH/DELETE | `/api/projects/{projectId}/members/{userId}` | |
| GET | `/api/projects/{projectId}/environments` | |
| GET/POST | `/api/projects/{projectId}/resources` | create `{name, slug, description, schemaDefinition}` |
| GET/PATCH/DELETE | `/api/projects/{projectId}/resources/{slug}` | |
| GET/POST | `/api/projects/{projectId}/resources/{slug}/schema-versions` | publish `{schemaDefinition}`; versions are immutable |
| GET/POST | `/api/projects/{projectId}/environments/{env}/resources/{slug}/records` | list: `limit`, `sort`, `order`, `cursor`, `filter[field]=<json>` |
| GET/PATCH/DELETE | `.../records/{recordId}` | PATCH uses `application/merge-patch+json`; PATCH and DELETE need `If-Match: <etag>` |
| GET/POST | `/api/projects/{projectId}/api-keys` | create `{name, environmentSlug, expiresAt?}` and get back `{apiKey, secret}`; the secret is shown once |
| GET/DELETE | `/api/projects/{projectId}/api-keys/{keyId}` | DELETE revokes the key |
| * | `/api/v1/projects/{projectId}/resources/{slug}[/{recordId}]` | Generated public CRUD; `Authorization: Bearer relay_...`. The key's environment decides the data scope. |

Schema definition: `{"type":"object","properties":{"title":{"type":"STRING","required":true,"unique":false,"default":...}}}`.
Field types: `STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, JSON`.

## Added (V7+)

### Custom APIs (declarative workflows)
| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/projects/{projectId}/custom-apis` | create `{name, slug, description, method, definition}` |
| GET/DELETE | `/api/projects/{projectId}/custom-apis/{slug}` | |
| GET/POST | `/api/projects/{projectId}/custom-apis/{slug}/versions` | publish `{method, definition}`; returns an immutable new version |
| POST | `/api/projects/{projectId}/environments/{env}/custom-apis/{slug}/run` | console test run with `{input:{...}}` |
| GET/POST | `/api/v1/projects/{projectId}/functions/{slug}` | public invocation with a Bearer key. Input comes from the JSON body (POST) or query params (GET). |

Custom API response shape: `{id, projectId, name, slug, description, currentVersion, method, definition, createdAt, updatedAt}`.
Version shape: `{id, version, method, definition, createdAt}`.
Run result: `{status, body, steps:[{id, type, ok}]}`.

Definition shape:
```json
{
  "steps": [
    {"id": "find", "type": "list", "resource": "todos", "filter": {"done": false}, "limit": 10},
    {"id": "make", "type": "create", "resource": "todos", "data": {"title": "{{input.title}}", "done": false}},
    {"id": "one", "type": "get", "resource": "todos", "recordId": "{{input.id}}"},
    {"id": "upd", "type": "update", "resource": "todos", "recordId": "{{input.id}}", "data": {"done": true}},
    {"id": "del", "type": "delete", "resource": "todos", "recordId": "{{input.id}}"}
  ],
  "response": {"status": 200, "body": {"created": "{{steps.make}}", "open": "{{steps.find.data}}"}}
}
```
A string that is exactly `{{path}}` is replaced by the JSON value at that path. Placeholders inside a longer string are interpolated as text.
Roots are `input` and `steps.<id>`. A record step returns the record `{id, createdAt, updatedAt, schemaVersion, etag, data}`, a list step returns `{data:[records], pagination:{limit, nextCursor}}`, and a delete step returns `{deleted:true, id}`. There can be at most 20 steps.
Workflows are interpreted data. No user code is ever executed.

Version resolution: if the environment has an active deployment, the version pinned by that release is used.
Otherwise `sandbox` uses the latest version, and `development`/`production` return `409 NOT_DEPLOYED`.

### Module catalog
| Method | Path | Notes |
|---|---|---|
| GET | `/api/modules` | `[{key, name, description, version, resources:[{name, slug, schemaDefinition}], customApis:[{name, slug, method, definition}]}]` |
| GET | `/api/projects/{projectId}/modules` | installations `[{moduleKey, moduleVersion, installedAt, installedBy}]` |
| POST | `/api/projects/{projectId}/modules/{key}/install` | creates the module's resources and custom APIs atomically. Returns `409` if a slug clashes or the module is already installed. |

### Releases and deployments
| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/projects/{projectId}/releases` | create `{notes}` to take an immutable snapshot of current schema and custom-API versions |
| GET | `/api/projects/{projectId}/releases/{version}` | `{id, version, notes, snapshot, createdBy, createdAt}` |
| GET | `/api/projects/{projectId}/deployments` | history `[{id, releaseVersion, environment, action, createdBy, createdAt}]`, newest first |
| GET | `/api/projects/{projectId}/deployments/active` | `[{environment, releaseVersion, deployedAt}]` for each environment (`releaseVersion` may be null) |
| POST | `/api/projects/{projectId}/environments/{env}/deployments` | `{releaseVersion}` |
| POST | `/api/projects/{projectId}/environments/{env}/rollback` | re-deploys the release that was active before the current one (`action: ROLLBACK`). Returns `409 NOTHING_TO_ROLLBACK` if there isn't one. |

### Quotas, rate limits and usage
| Method | Path | Notes |
|---|---|---|
| GET | `/api/projects/{projectId}/usage?days=30` | `{quota:{dailyRequestLimit, requestsPerMinute}, today:{sandbox, development, production}, daily:[{date, environment, requests}]}` |
| PUT | `/api/projects/{projectId}/quota` | `{dailyRequestLimit, requestsPerMinute}` (managers only) |

Every `/api/v1/**` request is counted per project and environment. Going over a limit returns `429` with `RATE_LIMITED` or `QUOTA_EXCEEDED`.

### Export / handoff
| Method | Path | Notes |
|---|---|---|
| GET | `/api/projects/{projectId}/export` | JSON bundle containing the project, environments, resources with all schema versions, custom APIs with versions, releases, deployments, module installations and an OpenAPI 3 document of the generated API. Add `?includeRecords=true` to include live records for each environment. |

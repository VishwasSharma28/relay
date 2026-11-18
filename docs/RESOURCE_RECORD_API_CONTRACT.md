# Generated Resource Records API Contract

**Status:** Implemented API contract (record behavior plus API-key authentication)
**Scope:** Generated record CRUD endpoints for project resources

This document describes generated JSONB record access with environment-scoped
bearer keys. The implementation uses the Relay response envelope and the
supported schema subset described below.

## Authentication and tenant scope

Every generated record request uses an environment-specific API key. API-key
management requests use the existing authenticated session:

```http
Authorization: Bearer <api-key>
```

The key determines the environment for `/api/v1` generated record routes.
Clients do not supply an environment ID in that route or request body. The
project UUID in the route must match the project associated with the key. A
missing, invalid, expired, or revoked key returns `401`. A valid key used with
a different project returns `404` to avoid revealing whether that project
exists. The existing `/api/projects/{projectId}/environments/{environmentSlug}`
record route also accepts bearer keys, but verifies both path scopes against
the key. Existing session authentication remains supported there.

API-key management uses the authenticated session API:

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/api/projects/{projectId}/api-keys` | OWNER or ADMIN; returns the secret once |
| GET | `/api/projects/{projectId}/api-keys` | Project member; metadata only |
| GET | `/api/projects/{projectId}/api-keys/{keyId}` | Project member; metadata only |
| DELETE | `/api/projects/{projectId}/api-keys/{keyId}` | OWNER or ADMIN; revokes the key |

Creation accepts `name`, `environmentSlug` (`sandbox`, `development`, or
`production`), and an optional future `expiresAt`. The secret uses
`relay_sandbox_`, `relay_dev_`, or `relay_live_` according to its environment.
It contains 256 random bits. PostgreSQL stores only its SHA-256 hash and a
non-secret 24-character lookup prefix. The full secret is never returned by
list, detail, or revoke operations.

## Routes

Base path:

```text
/api/v1/projects/{projectId}/resources/{resourceSlug}
```

| Method | Path | Behavior |
| --- | --- | --- |
| GET | Base path | List active records |
| GET | Base path `/{recordId}` | Retrieve one active record |
| POST | Base path | Create a record |
| PATCH | Base path `/{recordId}` | Partially update a record |
| DELETE | Base path `/{recordId}` | Soft-delete a record |

`projectId` and `recordId` are UUIDs. `resourceSlug` identifies a resource
within the project. Deleted resources and records are not addressable through
these routes.

## JSON formats

Requests and responses use UTF-8 JSON. `Content-Type: application/json` is
required for `POST`. `PATCH` requires
`Content-Type: application/merge-patch+json`.

Create and patch bodies contain the resource fields directly as a JSON object.
Clients do not send an `id`, timestamps, `schemaVersion`, `etag`, or a `data`
wrapper. These are server-managed fields.

Example create request:

```http
POST /api/v1/projects/9ef.../resources/tasks
Content-Type: application/json
```

```json
{
  "title": "Review migration",
  "complete": false,
  "priority": 2
}
```

A single-record response uses the Relay envelope; its `data` value has this shape:

```json
{
  "id": "8c50d290-71da-4ccb-8b41-b9ec8495c208",
  "createdAt": "2026-11-10T07:30:00Z",
  "updatedAt": "2026-11-10T07:30:00Z",
  "schemaVersion": 3,
  "etag": "\"relay-record-8c50d290-71da-4ccb-8b41-b9ec8495c208-v1\"",
  "data": {
    "title": "Review migration",
    "complete": false,
    "priority": 2
  }
}
```

Timestamps are ISO 8601 UTC instants. `schemaVersion` is the resource-local
integer schema version against which this record was last successfully
validated. `etag` is the exact strong entity tag accepted by `If-Match`.
`deletedAt` is omitted because deleted records are not returned.

`POST` returns `201 Created`, the record response, a matching `ETag` header,
and a `Location` header for the new record. Single-record `GET` and `PATCH`
return `200 OK`, the record response, and the matching `ETag` header. The list
response includes an `etag` in each item so a client can conditionally update
or delete an item without first retrieving it individually. `DELETE` returns
`204 No Content`; its `ETag` header identifies the incremented tombstone
version.

## PATCH behavior

PATCH follows RFC 7396 JSON Merge Patch semantics:

- The body must be a JSON object.
- Omitted properties are unchanged.
- A property set to `null` is removed from the record. It cannot be used to
  assign an explicit JSON `null` value through PATCH.
- Nested objects are merged recursively; arrays are replaced as a whole.
- The complete merged object is validated against the effective schema before
  the update is written.
- An empty object is a successful no-op and leaves `updatedAt`, the record
  version, and ETag unchanged.

To store an explicit null, a schema must allow it and the client must use a
create operation or another future operation with replacement semantics.

The response-only fields `id`, `createdAt`, `updatedAt`, `schemaVersion`, and
`etag` are reserved and cannot be declared as resource properties or written
in a request. Attempts to write them return `422 Unprocessable Content`.

## Listing, filtering, sorting, and pagination

The list endpoint accepts these query parameters:

| Parameter | Behavior |
| --- | --- |
| `limit` | Page size from 1 through 100; default 25. |
| `cursor` | Opaque cursor returned by the previous page; omit on the first page. |
| `sort` | `createdAt`, `updatedAt`, or `id`; default `createdAt`. |
| `order` | `asc` or `desc`; default `desc`. |
| `filter[field]` | Exact match for a top-level scalar field declared by the effective schema. |

Sorting always adds `id` as a tie-breaker, making traversal deterministic when
timestamps match. Dynamic-field sorting is excluded from v1.

Each filter value is a JSON scalar encoded as a query parameter. For example,
`filter[complete]=false` matches a boolean and
`filter[title]=%22Review%20migration%22` matches that exact string. Filters on
different fields are combined with AND. Filtering an undeclared field,
repeating the same field, or supplying an array/object value returns `400 Bad
Request`. Range, substring, and OR filters are excluded from v1.

Pagination uses keyset traversal, not offsets. The opaque cursor is bound to
the resource, sort, order, and filters used for the list. Reusing it with
different list parameters returns `400 Bad Request`. The API does not promise
a snapshot: records created or updated while pages are being traversed may not
appear consistently across that traversal.

List response (wrapped in the Relay success envelope):

```json
{
  "success": true,
  "data": {
    "data": [
      {
        "id": "8c50d290-71da-4ccb-8b41-b9ec8495c208",
        "createdAt": "2026-11-10T07:30:00Z",
        "updatedAt": "2026-11-10T07:30:00Z",
        "schemaVersion": 3,
        "etag": "\"relay-record-8c50d290-71da-4ccb-8b41-b9ec8495c208-v1\"",
        "data": { "title": "Review migration" }
      }
    ],
    "pagination": {
      "limit": 25,
      "nextCursor": "eyJ..."
    }
  }
}
```

`nextCursor` is `null` when no further page is available.

## Resource schema format

The schema document is a JSON object with exactly `type` and `properties` at
the root; `type` must equal `"object"`. Each property is a field definition
with `type` (one of `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`,
`DATETIME`, or `JSON`), and optional boolean `required` and `unique` plus an
optional type-matching `default`. Unsupported keywords, field types, invalid
field names, and response-reserved field names are rejected. This is Relay's
compact field-schema format, not full JSON Schema Draft 2020-12. Publishing a
schema creates an immutable version; records retain the schema version used
for their last successful validation.
## Schema compatibility and environment resolution

Every record stores the schema version against which its data was last
successfully validated. A schema publication never rewrites existing record
data. Reads return stored data and its stored `schemaVersion` without
revalidating or transforming it.

On create, Relay validates the submitted object against the effective schema
for the API key's environment and saves that schema version with the record.
On PATCH, Relay merges the patch into the stored object, validates the full
result against the environment's effective schema, and only then writes both
the data and the new schema-version reference. A legacy record remains
readable, but an update can fail if the complete resulting record does not
conform to the effective schema. There is no automatic record migration.

Effective schema resolution:

1. Before release deployments are available, use the resource's current
   schema version.
2. Once release deployments are available, use the schema version captured by
   the active deployment for the API key's environment.
3. Production with no active deployment returns `503 Service Unavailable`
   (`environment_not_deployed`). It must not fall back to an unpublished
   current schema.
4. For Sandbox and Development without an active deployment after releases
   become available, the proposal is to use the current schema as a draft
   fallback. An active deployment in either environment takes precedence.
5. If an active deployment exists but does not contain the requested resource,
   return `404`; do not expose unpublished resource configuration.

The Sandbox/Development fallback in item 4 is a proposal for final review; the
Production rule in item 3 is approved.

## ETags and conditional writes

Every record has a positive, monotonically increasing `record_version`,
starting at `1`. The strong ETag format is:

```text
"relay-record-{recordId}-v{record_version}"
```

The server returns it in the HTTP `ETag` header for single-record GET, POST,
and PATCH responses and in each record representation for list responses.
The header is required on PATCH and DELETE as `If-Match`. Exactly one strong
ETag is accepted; a missing header returns `428 Precondition Required`. A
stale/non-matching ETag returns `412 Precondition Failed`. Weak tags, wildcard
`*`, or multiple tags return `400 Bad Request`.

The server checks the version and writes the change atomically. PATCH and
DELETE update only a row whose ID, project, environment, resource, active
state, and `record_version` still match the values read for the request. A
successful data update or soft delete increments `record_version` by one.
Concurrent writes using the same ETag therefore cannot both succeed. A PATCH
no-op keeps the current version and ETag. DELETE increments the version and
returns the tombstone ETag in its `ETag` response header.

## Errors and status codes

Errors use the existing Relay JSON response envelope:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SCHEMA_VALIDATION_FAILED",
    "message": "Record data does not match the resource schema",
    "details": { "/priority": "value must match type INTEGER" }
  }
}
```

| Status | Cases |
| --- | --- |
| `400` | Malformed JSON, invalid UUID or query parameter, invalid cursor/filter, or invalid key request data. |
| `401` | Missing, invalid, expired, or revoked API key; missing session for management APIs. |
| `403` | Authenticated project member without the role needed to create or revoke API keys. |
| `404` | Unknown project/resource/record, deleted resource/record, project/environment mismatch, or missing API key metadata. |
| `422` | Valid JSON with an invalid record shape or schema validation errors. |
| `428` | Missing `If-Match` on PATCH or DELETE. |
| `412` | ETag does not match the current active record version. |
| `500` | Unexpected server error; response detail does not expose SQL or internal implementation data. |

For privacy, a key used with another project or environment receives the same
`404` shape as an unknown resource or record. Invalid authentication responses
do not reveal whether a key prefix exists.

## Flyway migration history

The current ordered migrations are V1 through V7. V5 adds record
schema-version references and optimistic versions. V6 creates `api_keys` with
a composite project/environment foreign key, unique non-secret lookup prefix
and hash, expiry/revocation metadata, and lookup indexes. Future schema
changes use new migrations; applied migration files are never modified.

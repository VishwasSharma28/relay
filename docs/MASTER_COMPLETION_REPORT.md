# Relay: master completion report

> **Integration note (2026-11-18):** This report was included in the supplied archive and describes its source checkout (`feature/relay-complete`, macOS, Node 26). The current repository is `feature/relay-claude-complete`. In this worktree, `.\mvnw.cmd clean verify` passed (38 tests); frontend lint passed, 19 frontend tests passed, and the production build succeeded with Node 24.14.1. Docker Compose configuration validated, but a Docker daemon was unavailable, so the container build and browser smoke test below were not rerun here.

- **Branch:** `feature/relay-complete`, based on `main` at `553e877`, the only branch.
- **Date:** 2026-11-18
- **Verification environment:** macOS, OpenJDK 21.0.13, PostgreSQL 17 (Homebrew, port 5434), Node 26.
- **Docker:** not available on the build machine. See Blocked.

## 1. Original status (audit of `main`)
| Area | Status at start |
|---|---|
| Auth: register, login, logout, sessions, CSRF, platform roles, admin bootstrap | Complete, tested |
| Projects, members, member roles (OWNER/ADMIN/MEMBER), auto-created sandbox/development/production environments | Complete, tested |
| Resources, immutable schema versions (DB trigger), JSONB records with validation, unique fields, ETag/If-Match, cursor pagination, filters | Complete, tested |
| API keys: hashed, prefixed, environment-scoped, expiry, revocation; generated `/api/v1` CRUD | Complete, tested |
| Custom APIs and workflows, module catalog, releases, deployments, rollback, quotas, rate limiting, usage, export | Missing |
| Frontend | Missing. The README described it as "JSF", but there was no frontend code. |
| Profiles, Dockerfile, health checks, CI, docs | Missing. There was a single `application.properties` and a 2-line README. |
| Tests | 25 passing (8 classes) |

## 2. Newly implemented
| Feature | Where |
|---|---|
| Declarative workflow engine: create, get, list, update and delete steps, `{{input.x}}` / `{{steps.id...}}` templates, max 20 steps, atomic (one transaction) | `services/WorkflowEngine.java` |
| Custom APIs with immutable versions, console test run, public `/api/v1/projects/{id}/functions/{slug}` | `CustomApiService`, `CustomApiRepository`, `PlatformController`, `PublicFunctionController` |
| Module catalog (todo-list, blog, contact-form), validated at startup, atomic install | `ModuleService`, `resources/modules/catalog.json` |
| Immutable releases (snapshot of schema and custom-API versions), append-only deployments, rollback, versions pinned per environment | `ReleaseService`, `ReleaseRepository` |
| Per-project quotas and per-minute rate limits, stored in PostgreSQL so they hold across instances; daily usage per environment | `UsageService`, wired into `ApiKeyAuthenticationFilter` |
| Export bundle: project, environments, resources and all schema versions, custom APIs and versions, releases, deployments, installations, generated OpenAPI 3.0.3, optional records | `ExportService` |
| Frontend console: all 13 required screens connected to real endpoints | `frontend/` |
| SPA serving from the jar | `SpaForwardingController` |
| dev/prod profiles, actuator health (liveness and readiness only), graceful shutdown, ECS JSON logs in prod, env-based secrets | `application*.properties` |
| Dockerfile (multi-stage, non-root, HEALTHCHECK), compose file, `.env.example`, CI workflow | repo root, `.github/workflows/ci.yml` |

## 3. Database migrations
`V7__custom_apis_modules_releases_usage.sql` is new. It adds:
- `custom_apis`, with a partial unique active slug
- `custom_api_versions` and `releases`, both protected by an UPDATE-blocking trigger
- `deployments`, append-only
- `project_module_installations`
- `project_quotas`, `usage_daily` and `usage_minute`

All tables carry composite `(project_id, …)` foreign keys, following the existing tenant-safety pattern. V1–V6 were not modified.

## 4. Architecture notes
- **Request flow:** Request → `ApiKeyAuthenticationFilter`, for `/api/v1/**` and bearer record routes. The filter authenticates the key and then calls `UsageService.recordAndCheck`, which returns a 429 when a limit is hit. Requests then go to the controller and on to the service, where `ProjectAccessService` checks membership or manager role. The service calls a JDBC repository.
- **Workflows:** workflows call `RecordService` with a synthetic environment-scoped principal. They therefore get exactly the same validation, uniqueness and isolation as the generated CRUD API. No user code is compiled or executed.
- **Version resolution:** if the environment has an active deployment, its release's pinned version is used. Otherwise sandbox uses the latest version, and development/production return `409 NOT_DEPLOYED`.

## 5. Tests and exact results
| Suite | Result |
|---|---|
| `./mvnw clean verify` (macOS equivalent of `mvnw.cmd`) against real PostgreSQL 17 | **38 tests, 0 failures, 0 errors, 0 skipped.** This is 25 original tests plus 13 new ones: `PlatformApiTests` (9 integration) and `WorkflowEngineTests` (4 unit). |
| Frontend `npm run lint` | 0 errors, 0 warnings |
| Frontend `npm test` (Vitest) | 4 files, 19 tests passed |
| Frontend `npm run build` | Succeeded: about 228 kB JS (69 kB gzipped) and 9 kB CSS |
| Production jar with the `prod` profile | `/actuator/health/readiness` returns UP. JSON logs confirmed. SIGTERM triggers a graceful shutdown. `XSRF-TOKEN` cookie is `Secure; SameSite=Lax`. |
| Browser smoke test (jar with bundled frontend + PostgreSQL) | Register, auto-login, create a project, install the todo-list module, run the `add-todo` workflow from the console, and see the record in the records explorer all worked. |

The new integration tests cover:
- workflow execution in the console and via API key
- transactional rollback of a failed multi-step workflow
- rejection of unknown and code-like workflow keywords
- MEMBER vs OWNER permissions, including 403 on writes
- 404 for outsiders (IDOR)
- cross-project key rejection and environment data isolation
- HTTP method enforcement
- release snapshots, deploy, per-environment version pinning, rollback, `NOTHING_TO_ROLLBACK`
- release immutability enforced by the database
- atomic module install, including no partial install on a slug clash
- rate limit (`RATE_LIMITED`) and daily quota (`QUOTA_EXCEEDED`)
- usage counts, export contents and the OpenAPI document
- that only the health actuator endpoint is exposed

**Bugs found and fixed during verification:**
1. Absent optional workflow inputs became explicit `null`, which failed schema validation. They are now omitted so schema defaults apply.
2. The SPA forwarding pattern caught `/assets/*`, so the frontend failed to load from the jar.
3. The CSRF cookie was not marked `Secure` in production.

## 6. Security review
- **SQL injection:** all SQL uses bound parameters. Dynamic SQL in `RecordRepository.list` only appends whitelisted sort columns and generated parameter names. The new repositories use static SQL only.
- **IDOR and tenant isolation:**
  - Every console service call checks project membership, and non-members get 404.
  - API-key calls compare the path project with the key's project.
  - Composite foreign keys stop cross-tenant references at the database level.
- **Environment isolation:** the key's environment is always the data scope; a path cannot override it.
- **Secrets:**
  - DB credentials and the admin bootstrap come from environment variables only.
  - `.env` is git-ignored; only `.env.example` is committed.
  - API keys are stored as SHA-256 hashes.
  - No secrets are in the repository.
- **Sessions:**
  - Cookies are HttpOnly and SameSite=Lax, and Secure by default with `prod`.
  - Sessions have a configurable timeout.
  - CSRF protection is on for all session-authenticated mutations.
- **Code execution:** workflows are data interpreted against a fixed set of record operations, with no scripting, reflection or class loading.
- **Information leakage:** stack traces and messages are never included in error responses, and only the health actuator endpoint is exposed.
- **Concurrency:**
  - Version numbering for schemas, custom APIs and releases uses `SELECT … FOR UPDATE` on the parent row.
  - Deploy and rollback lock the environment row.
  - Usage counters use atomic upserts.

## 7. Feature classification
| Feature | Classification |
|---|---|
| Auth, sessions, roles, projects, members | Complete and verified |
| Environments (sandbox, development, production) | Complete and verified |
| Resources, immutable schemas, JSONB CRUD, validation, pagination | Complete and verified |
| API keys: bearer auth, revocation, expiry | Complete and verified |
| Custom APIs, versioning, workflow execution | Complete and verified |
| Module catalog and installations | Complete and verified |
| Releases, deployment history, rollback | Complete and verified |
| Quotas, rate limiting, usage tracking | Complete and verified |
| Export and backend handoff (JSON and OpenAPI) | Complete and verified |
| Frontend: all screens | Implemented and verified by unit tests and build. A live browser smoke test covered auth, projects, modules, custom API runs and records. The API keys, releases, usage and settings screens were not clicked through in the live test. |
| dev/prod profiles, health, graceful shutdown, structured logs | Complete and verified (jar run) |
| Dockerfile and compose | Implemented but unverified (no Docker on the build machine) |
| GitHub Actions CI | Implemented but unverified (not pushed) |

## 8. Known limitations and remaining manual tasks
1. **Blocked: Docker build and run.** Docker Desktop's install needs an admin password. Run `docker build -t relay .` and `docker compose up` to verify. CI also builds the image.
2. **Sessions are in memory.** Several instances need sticky sessions or Spring Session JDBC.
3. **Release pinning covers custom APIs only.** Record CRUD always validates against a resource's current schema version; releases record schema versions for audit and export but do not pin them per environment.
4. **Rollback target.** Rollback re-deploys the release that was active before the current one, so repeated rollbacks alternate between two releases. To go further back, deploy a specific release.
5. **Usage counting.** Requests rejected by rate or quota limits still count toward usage.
6. **No login brute-force throttling.** Rate limits apply to API-key traffic only. Add login throttling or put a WAF in front before going public.
7. **Workflow updates.** Update and delete steps read the current ETag inside the same transaction (last write wins within a run). They do not take a caller-supplied If-Match.
8. **The module catalog is static.** It ships with the application as a classpath JSON file; there is no third-party module publishing.
9. **Not done:** deployment to live infrastructure, by design (requires approval), and pushing the branch or opening a PR.

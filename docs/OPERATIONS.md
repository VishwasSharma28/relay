# Relay operations guide

## Runtime requirements
- Java 21 to run the jar, or Docker to run the image.
- PostgreSQL 15 or newer. Development and tests use 17. The `pgcrypto`-free `gen_random_uuid()` built-in is required.
- One process serves both the API (`/api/**`) and the bundled frontend.

## Configuration (environment variables)
| Variable | Required | Default | Purpose |
|---|---|---|---|
| `DB_URL` | prod | `jdbc:postgresql://localhost:5434/relay` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | yes | | Database credentials. These are never stored in files. |
| `SPRING_PROFILES_ACTIVE` | | none (the image uses `prod`) | `dev` or `prod` |
| `SESSION_COOKIE_SECURE` | prod | `true` with `prod` | Must be true behind HTTPS |
| `SESSION_TIMEOUT` | | `8h` | Console session lifetime |
| `PORT` | | `8080` | HTTP port |
| `DB_POOL_MAX` | | 10 (20 with `prod`) | Hikari pool size |
| `SHUTDOWN_TIMEOUT` | | `30s` | Graceful drain window |
| `RELAY_DEFAULT_DAILY_REQUESTS` | | `10000` | Default daily quota per project |
| `RELAY_DEFAULT_REQUESTS_PER_MINUTE` | | `120` | Default rate limit per project |
| `RELAY_ADMIN_EMAIL` / `RELAY_ADMIN_PASSWORD` / `RELAY_ADMIN_FULL_NAME` | | | Creates or promotes a platform admin at startup |

## Database provisioning
```sql
CREATE ROLE relay LOGIN PASSWORD '<secret>';
CREATE DATABASE relay OWNER relay;
```
The application user needs DDL rights on its own database, because Flyway runs migrations at startup.
If your policy separates migration and runtime roles, run Flyway with the owner role and grant the runtime role DML only.

## Migrations
- Flyway runs automatically on startup. Migrations live in `backend/src/main/resources/db/migration` (V1–V4, V6, V7) and `backend/src/main/java/db/migration` (V5, a Java migration).
- Never edit an applied migration. Every change goes in a new `V<n>__description.sql`.
- Migrations are forward-only. To undo a schema change, write a new compensating migration.
- Before you migrate production, take a backup (see below) and run the new version against a restored copy first.

## Build and deploy
```bash
docker build -t relay:<version> .
docker compose up -d          # uses .env; see .env.example
```
Without Docker:
```bash
cd frontend && npm ci && npm run build
cp -R dist ../backend/src/main/resources/static
cd ../backend && ./mvnw -B clean verify      # needs PostgreSQL at DB_URL
java -jar target/relay-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Health checks
- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness`. This includes the database.
- No other actuator endpoints are exposed.

### Graceful shutdown
On SIGTERM, Relay stops accepting connections and lets in-flight requests finish within `SHUTDOWN_TIMEOUT`.
Give your orchestrator a longer grace period: the compose file uses 40s.

### Scaling
- Rate-limit and quota counters live in PostgreSQL, so they hold across instances.
- Sessions are in memory, so several instances need sticky sessions until a shared session store such as Spring Session JDBC is added.

## Backups
- Take logical backups nightly at minimum, plus point-in-time recovery (WAL archiving or a managed-provider PITR) for production:
  ```bash
  pg_dump --format=custom --file=relay-$(date +%F).dump "$DATABASE_URL"
  ```
- To restore: `pg_restore --clean --if-exists --dbname="$DATABASE_URL" relay-YYYY-MM-DD.dump`.
- Test restores regularly. All tenant data is in this one database, so there is no separate file storage to back up.

## Rollback
- **Application (customer-facing):** the console's Releases page, or `POST /api/projects/{id}/environments/{env}/rollback`, re-deploys the previous release. This is an append-only history entry; nothing is deleted.
- **Platform version:** redeploy the previous image tag. This only works if no migration has been applied since then. Migrations are additive and forward-only, so rolling back across a migration means restoring the pre-upgrade backup.

## Logging
- With `prod`, the console logs use ECS-structured JSON (`logging.structured.format.console=ecs`). Ship stdout to your log platform.
- Error responses never include stack traces.

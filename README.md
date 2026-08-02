# SHRESTA-BE

Backend repository operational notes for local development, verification, and production runs.

## Dependencies

Use Java 21 or newer, Docker, Docker Compose, and the repository wrapper scripts.

```bash
java -version
docker --version
docker-compose --version
./scripts/be-java -version
./scripts/be-gradle --version
```

On macOS with Colima:

```bash
colima start
```

## Environment

Create a local environment file from the example:

```bash
cp .env.example .env
```

Common local values:

```bash
SERVER_PORT=8090
DATABASE_URL=jdbc:postgresql://localhost:5442/shresta
DATABASE_USERNAME=shresta_app
DATABASE_PASSWORD=change-me
REDIS_HOST=localhost
REDIS_PORT=6389
SHRESTA_ADMIN_API_KEY=local-shresta-admin-key
SHRESTA_MEDIA_ASSET_BASE_URL=http://localhost:9010/shresta-local-assets
SHRESTA_MEDIA_DELIVERY_MODE=s3-compatible-local
SHRESTA_ASSET_STORAGE_ROOT=var/shresta-assets
SHRESTA_ASSET_STORAGE_PROVIDER=s3-compatible
SHRESTA_ASSET_DELIVERY_MODE=s3-compatible-local
SHRESTA_ASSET_OBJECT_UPLOAD_ENABLED=true
SHRESTA_ASSET_OBJECT_ENDPOINT=http://localhost:9010
SHRESTA_ASSET_OBJECT_BUCKET=shresta-local-assets
SHRESTA_ASSET_OBJECT_REGION=us-east-1
SHRESTA_ASSET_OBJECT_ACCESS_KEY=shresta_minio
SHRESTA_ASSET_OBJECT_SECRET_KEY=shresta-local-minio-password
SHRESTA_ASSET_OBJECT_PATH_STYLE=true
```

Keep secrets out of commits. Use environment variables, a local `.env`, or the deployment secret manager.

## Local Services

Start PostgreSQL, Redis, and local S3-compatible object storage:

```bash
docker-compose -f docker-compose.dev.yml up -d
```

Check service status:

```bash
docker-compose -f docker-compose.dev.yml ps
```

Stop local services (preserves data):

```bash
docker-compose -f docker-compose.dev.yml down
```

Full reset — wipe all local data (Postgres, Redis, MinIO) and start clean:

```bash
docker-compose -f docker-compose.dev.yml down -v && docker-compose -f docker-compose.dev.yml up -d
```

After a full reset, restart the backend; Flyway and DatabaseSeeder rebuild everything automatically.

## Database

### Migrations

Schema migrations use a Java-based TransitionPlan framework (matches the Haskell euler-lsp pattern).

- **Entry point**: `src/main/java/db/migration/V1__Schema.java` — Flyway discovers and runs this on startup.
- **Table plans**: `src/main/java/com/shrestaexclusive/platform/db/migration/tables/` — one `*Migration.java` per table, each owning its full history as versioned transitions.
- **Framework**: `src/main/java/com/shrestaexclusive/platform/db/migration/framework/` — `TransitionPlan`, `Transition`, `MigrationRunner`.
- **Version tracking**: `shresta_table_migration_versions` table tracks per-table version independently of Flyway. Transitions are skipped if the table is already at or past their target version.

To add a future schema change:
1. Add a `.transition(List.of(N), N+1, List.of("ALTER TABLE ..."))` in the relevant `*Migration.java`.
2. Create a new `V2__<Description>.java` in `src/main/java/db/migration/` that calls `MigrationRunner.run(conn, List.of(<ThatMigration>.transitionPlan()))`.

### Seed Data

Seed data runs automatically on startup for `local`, `dev`, and `uat` profiles via `DatabaseSeeder`. It reads from JSON files in `src/main/resources/db/seed/` and upserts all rows idempotently — safe to re-run on every restart.

```
db/seed/
  category/   families, product-types, attributes, filters, tax, styling
  media/      reference-assets, product-assets, variants
  storefront/ home-sections, home-items
  store/      locator-sections, locations
  products/   items
  auth/       dev-accounts
```

To modify seed data, edit the relevant JSON file and restart the backend — the seeder re-upserts all rows.

### Media (MinIO)

The local object store is seeded from `seed/shresta-media/` by the `minio-setup` Docker container on every `docker-compose up`. This is separate from the database seeder.

Reseed MinIO manually (e.g. after replacing image files):

```bash
./scripts/seed-local-object-storage
```

Verify all media URLs are reachable:

```bash
./scripts/verify-shresta-media-s3
```

Verify that backend runtime code does not package or serve static media:

```bash
./scripts/verify-no-runtime-static-media
```

The same guard is wired into Gradle `check`.

## Development

Run the backend in development mode:

```bash
SPRING_PROFILES_ACTIVE=local ./scripts/be-gradle bootRun --no-daemon
```

Default URL:

```text
http://localhost:8090
```

Health check:

```bash
curl http://localhost:8090/api/v1/platform/health
```

API documentation:

```text
http://localhost:8090/swagger-ui/index.html
```

## UAT

UAT uses the same jar as production but with the `uat` profile, which activates `DatabaseSeeder` so seed data is loaded automatically on first start.

`.env.uat` ships pre-configured for local Docker services — no setup needed for local UAT.
For a real UAT server, replace the values in `.env.uat` with your server credentials.

Then start UAT with a single command:

```bash
./scripts/be-uat
```

The script builds the jar automatically if one does not exist. To force a rebuild first:

```bash
./scripts/be-gradle clean bootJar --no-daemon && ./scripts/be-uat
```

Upload the matching product images to the UAT bucket before starting so all media URLs resolve correctly.

## Production

Set up the env file once:

```bash
cp .env.prod.example .env.prod
# edit .env.prod and fill in all secrets
```

Then start production with a single command:

```bash
./scripts/be-prod
```

The script builds the jar automatically if one does not exist. To force a rebuild first:

```bash
./scripts/be-gradle clean bootJar --no-daemon && ./scripts/be-prod
```

**Required secrets that have no safe default — the process will fail to start or behave insecurely without them:**

| Variable | Purpose |
|---|---|
| `DATABASE_PASSWORD` | PostgreSQL authentication |
| `REDIS_PASSWORD` | Redis authentication |
| `SHRESTA_ADMIN_API_KEY` | Admin API gate |
| `JWT_PUBLIC_KEY_BASE64` | Customer JWT verification |
| `JWT_PRIVATE_KEY_BASE64` | Customer JWT signing |
| `RAZORPAY_KEY_ID` | Payment initiation |
| `RAZORPAY_KEY_SECRET` | Payment API authentication |
| `RAZORPAY_WEBHOOK_SECRET` | Payment webhook verification |
| `SHRESTA_ASSET_OBJECT_ACCESS_KEY` | Media upload to S3 |
| `SHRESTA_ASSET_OBJECT_SECRET_KEY` | Media upload to S3 |

**Do not set `SPRING_PROFILES_ACTIVE=local`, `dev`, or `uat` in production** — those profiles activate `DatabaseSeeder` which upserts seed data on every startup.

**Key prefix isolation**: always set `SHRESTA_KV_KEY_PREFIX=shresta:prod` in production and `shresta:uat` in UAT to prevent Redis key collisions across environments.

Health check endpoint for load balancer readiness probes:

```text
GET /actuator/health/readiness   → 200 when ready
GET /actuator/health/liveness    → 200 when live
```

## Docker

Build the image:

```bash
docker build -t shresta-be:local .
```

Run the image:

```bash
docker run --rm -p 8090:8090 --env-file .env shresta-be:local
```

## Verification

Run tests:

```bash
./scripts/be-gradle test --no-daemon
```

Run the production build gate:

```bash
./scripts/be-gradle clean test bootJar --no-daemon
```

## Troubleshooting

If Java is not found:

```bash
./scripts/be-java -version
```

If Gradle cannot connect to PostgreSQL or Redis:

```bash
docker-compose -f docker-compose.dev.yml ps
docker-compose -f docker-compose.dev.yml logs
```

If `./scripts/be-uat` fails with container name conflicts (for example `shresta-minio is already in use`), remove old SHRESTA containers from previous workspaces and retry:

```bash
docker rm -f shresta-minio shresta-minio-setup shresta-postgres shresta-redis 2>/dev/null || true
./scripts/be-uat
```

If media URLs return `404`, reseed/verify MinIO (DB seeding is separate and automatic):

```bash
./scripts/seed-local-object-storage
./scripts/verify-shresta-media-s3
./scripts/verify-no-runtime-static-media
```

If seed data is missing after startup, check that the active profile is `local`, `dev`, or `uat`:

```bash
SPRING_PROFILES_ACTIVE=local ./scripts/be-gradle bootRun --no-daemon
```

If port `8080` is busy:

```bash
lsof -nP -iTCP:8090 -sTCP:LISTEN
SERVER_PORT=8081 ./scripts/be-gradle bootRun --no-daemon
```

If migrations fail, inspect the local database and migration history:

```bash
docker-compose -f docker-compose.dev.yml exec postgres psql -U shresta_app -d shresta
```

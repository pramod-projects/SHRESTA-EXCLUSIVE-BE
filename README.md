# SHRESTA-BE

Backend repository operational notes for local development, verification, and production runs.

## Dependencies

Use Java 21 or newer, Docker, Docker Compose, and the repository wrapper scripts.
Gradle is executed via `gradlew` (generated in-repo), so a global Gradle install is not required.

### macOS install commands

Install core backend dependencies:

```bash
brew update
brew install --cask temurin@21
brew install docker docker-compose colima cloudflared
```

Start Docker runtime on macOS:

```bash
colima start
```

### Linux (Ubuntu/Debian) install commands

Install Java 21, Docker, Docker Compose plugin, and cloudflared:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk ca-certificates curl gnupg lsb-release

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"

curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
  | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list >/dev/null
sudo apt-get update
sudo apt-get install -y cloudflared
```

After group changes on Linux, sign out/sign in (or reboot) before running Docker without sudo.

For a fresh clone on macOS, install Java 21 first:

```bash
brew install --cask temurin@21
```

Then verify Java 21 is selected:

```bash
/usr/libexec/java_home -V
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
```

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

### Option A (recommended): Docker services

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

### Option B: Native install (PostgreSQL + Redis + MinIO)

macOS:

```bash
brew update
brew install postgresql@16 redis minio/stable/minio minio/stable/mc
brew services start postgresql@16
brew services start redis
mkdir -p "$HOME/minio-data"
MINIO_ROOT_USER=shresta_minio MINIO_ROOT_PASSWORD=shresta-local-minio-password \
  minio server "$HOME/minio-data" --address ":9010" --console-address ":9011"
```

Linux (Ubuntu/Debian):

```bash
sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib redis-server curl
sudo systemctl enable --now postgresql
sudo systemctl enable --now redis-server

curl -LO https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x minio
sudo mv minio /usr/local/bin/minio
mkdir -p "$HOME/minio-data"
MINIO_ROOT_USER=shresta_minio MINIO_ROOT_PASSWORD=shresta-local-minio-password \
  minio server "$HOME/minio-data" --address ":9010" --console-address ":9011"
```

Create MinIO bucket and access alias:

```bash
mc alias set local http://127.0.0.1:9010 shresta_minio shresta-local-minio-password
mc mb --ignore-existing local/shresta-local-assets
```

Create backend DB/user in local PostgreSQL:

```bash
psql postgres <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'shresta_app') THEN
    CREATE ROLE shresta_app LOGIN PASSWORD 'change-me';
  END IF;
END
$$;

CREATE DATABASE shresta OWNER shresta_app;
GRANT ALL PRIVILEGES ON DATABASE shresta TO shresta_app;
SQL
```

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

## Persistent UP/DOWN stack (BE + FE + cloudflared)

From this repository root (`SHRESTA-EXCLUSIVE-BE`), use:

```bash
./up
./status
./FE_URL
./stack-control.sh logs be
./stack-control.sh logs fe
./stack-control.sh logs cloudflared-proxy
./stack-control.sh logs cloudflared
./down
```

Behavior:

- `UP` starts backend (`./scripts/be-uat`), frontend (`npm run start` in sibling FE repo), local cloudflared proxy (`node ./scripts/cloudflared-proxy.mjs`), and cloudflared (`cloudflared tunnel --url http://127.0.0.1:3310 --no-autoupdate`).
- The cloudflared public URL now serves both FE pages and media files/videos from MinIO on the same domain/path (`/shresta-local-assets/...`).
- Services run through `nohup`, so they keep running when terminal closes or screen locks.
- They stop only when you run `DOWN`, manually kill processes, or machine shuts down.
- Requires sibling repos in the same parent folder: `SHRESTA-EXCLUSIVE-BE` and `SHRESTA-EXCLUSIVE-WEB-FE`.

Get the live cloudflared public URL:

```bash
./FE_URL

# or, directly from logs:
grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' .logs/cloudflared.log | tail -n 1
```

Open media via that URL (example):

```text
https://<your-trycloudflare-url>/shresta-local-assets/logos/SHRESTA.mp4
```

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
| `SHRESTA_RAZORPAY_WEBHOOK_SECRET` (or `RAZORPAY_WEBHOOK_SECRET`) | Payment webhook verification |
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

If Java 11 is picked, switch to Java 21 before running Gradle scripts:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
./scripts/be-gradle --version
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

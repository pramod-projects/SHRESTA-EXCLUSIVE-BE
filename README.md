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

Each environment has an independent template and runtime file. Files are not
derived or merged at runtime; the matching launcher loads exactly one file:

- DEV: `.env.dev.example` -> `.env.dev`
- UAT: `.env.uat.example` -> `.env.uat`
- PROD: `.env.prod.example` -> `.env.prod`

Create the local DEV file:

```bash
cp .env.dev.example .env.dev
```

Common local values:

```bash
SERVER_PORT=8090
SHRESTA_ENVIRONMENT_MODE=DEV
SHRESTA_STOREFRONT_URL=http://localhost:3010
SHRESTA_ADMIN_URL=http://localhost:3010/admin
SHRESTA_API_URL=http://localhost:8090
DATABASE_URL=jdbc:postgresql://localhost:5442/shresta
DATABASE_USERNAME=shresta_app
DATABASE_PASSWORD=change-me
REDIS_HOST=localhost
REDIS_PORT=6389
SHRESTA_ADMIN_API_KEY=local-shresta-admin-key
MEDIA_PUBLIC_BASE_URL=http://localhost:9010/shresta-local-assets
SHRESTA_MEDIA_DELIVERY_MODE=cloudflare-r2
R2_ACCOUNT_ID=local
R2_ENDPOINT=http://localhost:9010
R2_BUCKET_NAME=shresta-local-assets
R2_REGION=us-east-1
R2_ACCESS_KEY_ID=shresta_minio
R2_SECRET_ACCESS_KEY=shresta-local-minio-password
R2_PATH_STYLE=true
MEDIA_BROWSER_CACHE_MAX_AGE_SECONDS=3600
MEDIA_UPLOAD_URL_TTL=10m
MEDIA_CANONICAL_MAX_WIDTH=2400
MEDIA_CANONICAL_MAX_HEIGHT=3200
MEDIA_CANONICAL_MAX_FILE_SIZE=15000000
MEDIA_VIDEO_MAX_FILE_SIZE=100000000
```

Keep all runtime `.env.<mode>` files out of commits. Commit only the `.example` templates.

## Local Services

### Option A (recommended): controller-managed Docker services

`./up dev` starts and health-checks PostgreSQL, Redis, and local S3-compatible object storage through Docker Compose before starting the applications. `./down` stops controller-owned containers while preserving their data volumes.

The following commands are for direct dependency maintenance only, not normal stack startup:

```bash
docker-compose --env-file .env.dev -f docker-compose.dev.yml up -d
```

Check service status:

```bash
docker-compose --env-file .env.dev -f docker-compose.dev.yml ps
```

Stop local services (preserves data):

```bash
docker-compose --env-file .env.dev -f docker-compose.dev.yml down
```

Full reset — wipe all local data (Postgres, Redis, MinIO) and start clean:

```bash
docker-compose --env-file .env.dev -f docker-compose.dev.yml down -v && docker-compose --env-file .env.dev -f docker-compose.dev.yml up -d
```

After a full DEV reset, restart the backend; Flyway and DatabaseSeeder rebuild the complete local catalog automatically.

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

`DatabaseSeeder` always upserts the website section definitions and three logo records so a fresh environment can render the branded storefront shell. The full catalog, product media records, stores, category configuration, and test login account are seeded automatically only for `local` and `dev`. UAT and PROD start with no products by default.

```
db/seed/
  category/   families, product-types, attributes, filters, tax, styling
  media/      reference-assets, product-assets
  storefront/ home-sections, home-items
  store/      locator-sections, locations
  products/   items
  auth/       dev-accounts
```

To modify DEV seed data, edit the relevant JSON file and restart the backend. All bootstrap and DEV seed operations are idempotent.

### Media (MinIO)

The local object store is seeded from `seed/shresta-media/` by the `minio-setup` Docker container on every `docker-compose up`. This is separate from the database seeder.

Reseed MinIO manually (e.g. after replacing image files):

```bash
./scripts/seed-local-object-storage
```

Verify all media URLs are reachable through the configured delivery domain:

```bash
./scripts/verify-media-delivery
```

Verify that backend runtime code does not package or serve static media:

```bash
./scripts/verify-no-runtime-static-media
```

The same guard is wired into Gradle `check`.

## Direct R2 Media

Admin media uses one upload flow:

1. The browser asks `POST /api/v1/admin/assets/upload-authorizations` for authorization.
2. Spring validates the admin role, product, media type, dimensions, file size, duration, and per-product count.
3. Spring creates an immutable UUID object key and a short-lived presigned PUT URL.
4. The browser PUTs the canonical file directly to R2. Media bytes never pass through Next.js or Spring Boot.
5. The browser calls `POST /api/v1/admin/assets/upload-completions`.
6. Spring performs R2 `HEAD` verification and changes the database row from `PENDING_UPLOAD` to `READY` only when size, content type, and media identity match.

R2 contains one canonical object per logical upload. The application does not create thumbnails, responsive variants, WebP/AVIF derivatives, or replacement objects at stable paths. Cloudflare Image Resizing is not used; frontend layout and CSS control display dimensions while loading the canonical object through the custom media domain.

Product primary/gallery assignment accepts only `READY` `PRODUCT_IMAGE` assets owned by that product. Product video assignment likewise accepts only one `READY` `PRODUCT_VIDEO` asset owned by the product; arbitrary external video URLs are not accepted. `V37__RemoveLegacyMediaDerivatives` removes obsolete LQIP metadata, and `V38__CanonicalProductVideoMedia` adds the product-video media FK and clears legacy bestseller video URL strings.

Creating a product uses a backend-owned media reservation:

1. The Add Product form requests `POST /api/v1/admin/assets/product-media-reservations`.
2. Spring returns an expiring product key owned by the authenticated admin actor.
3. Every product image/video authorization uses that key. Each upload receives a distinct backend UUID (`mediaId`), UUID-derived `assetKey`, and immutable object key.
4. Product submission verifies every `(mediaId, assetKey)` pair, media type, READY status, product ownership, reservation owner, and expiry before changing the reservation from `ACTIVE` to `SUBMITTED`.
5. Approval locks and revalidates the submitted reservation and all selected media, creates the product with its mandatory primary image, and changes the reservation to `CONSUMED` in the same transaction.
6. Rejection archives newly uploaded product media. A scheduled maintenance task changes expired, never-submitted reservations to `EXPIRED` and removes their objects through the normal after-commit R2 deletion path. Submitted reservations are never removed by expiry cleanup while awaiting review.

`DISPLAY_IMAGE` uploads, including logos and other frontend display media, do not use product reservations and do not submit product media-ID fields. They retain the existing backend-generated upload UUID only for immutable object identity and R2 completion verification.

R2 CORS must allow the storefront/admin origins, `PUT`, and the headers returned in `requiredHeaders`. Credentials are server-only: `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` must never be exposed through `NEXT_PUBLIC_*` variables.

Active `.env.*` files are local deployment inputs and are ignored by Git. Create them from the tracked `.env.*.example` templates. If a real credential is ever committed, remove the active file from tracking and rotate the credential immediately; deleting or untracking the file does not remove it from Git history.

Environment contracts:

- DEV: storefront/admin `http://localhost:3010`, API `http://localhost:8090`, local MinIO R2-compatible target.
- UAT: storefront `https://uat.shrestaexclusive.com`, admin `https://uat-admin.shrestaexclusive.com`, API `https://uat-api.shrestaexclusive.com`, browser cache `2592000` seconds.
- PROD: storefront `https://shrestaexclusive.com`, admin `https://admin.shrestaexclusive.com`, API `https://api.shrestaexclusive.com`, browser cache `604800` seconds.

The current platform has one global catalog and global admin ACL rather than tenant/business ownership records. Product ownership validation therefore means that the product must exist in the managed `bestsellers` catalog and the caller must hold an allowed admin role. Add a business/tenant relation before introducing multi-business accounts.

## Development

Start the complete DEV stack from either repository root:

```bash
./up dev
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

UAT uses the same jar as production with the `uat` profile. On a fresh database it bootstraps only website section copy and logo media records; products, product assets, stores, and test accounts are not seeded.

Create `.env.uat` from its independent template and fill in UAT-only service credentials:

```bash
cp .env.uat.example .env.uat
```

UAT uses Cloudflare R2 and must not reuse DEV MinIO values or PROD credentials. The UAT backend launcher runs `scripts/verify-provider-readiness` and refuses placeholder R2 values, a private R2 endpoint used as the public URL, wrong UAT domains, or a cache lifetime other than `2592000` seconds.

Then start UAT with a single command:

```bash
./up uat
```

### Controlled email provider verification

Run this only after automated tests pass and `.env.uat` contains complete credentials for every enabled provider. The verifier follows `SHRESTA_EMAIL_PROVIDER_ORDER`, selects only providers whose enablement flags are true, starts an isolated PostgreSQL container for each selected provider, enqueues one application-owned `ACCOUNT_SECURITY` notification, processes it through the real outbox worker and router, and requires an acceptance identifier from exactly one selected provider. It sends one message per enabled provider to one controlled mailbox.

```bash
export SHRESTA_EMAIL_LIVE_RECIPIENT=controlled-mailbox@example.com
export SHRESTA_EMAIL_LIVE_CONFIRM=SEND_EXACTLY_ONE_PER_PROVIDER
./scripts/verify-live-email-providers
```

Do not put `SHRESTA_EMAIL_LIVE_RECIPIENT` or `SHRESTA_EMAIL_LIVE_CONFIRM` in committed environment templates. The script loads `.env.uat`, validates required send credentials for enabled providers without printing them, requires at least one enabled provider in the configured order, forces UAT recipient containment, disables every non-selected adapter for each run, and stops on the first non-accepted outcome. Provider acceptance is not recipient delivery: after the script passes, confirm the reported number of inbox messages and inspect authenticated webhook processing/final delivery states before recording live verification as complete. Providers disabled for a partial UAT verification remain unverified until separately enabled and tested.

The controller builds the frontend and backend artifacts when required, verifies local service health, and starts the named UAT backend tunnel only after the UAT API reports ready.

Create products through the admin workflow, then upload their canonical media through the browser authorization/direct-PUT/completion flow. Do not pre-populate UAT R2 through a separate upload path.

## Persistent UP/DOWN stack (BE + FE + backend tunnel)

From this repository root (`SHRESTA-EXCLUSIVE-BE`), use:

```bash
./up          # DEV (default)
./up dev      # .env.dev in both repos
./up uat      # .env.uat in both repos
./up prod     # .env.prod in both repos
./status
./stack-control.sh logs be
./stack-control.sh logs fe
./stack-control.sh logs cloudflared-be
./down
```

Behavior:

- `./up <environment>` loads the matching `.env.<environment>` file in both repos and selects `./scripts/be-<environment>` for the backend. Omitting the environment starts DEV.
- The selected mode is recorded in `.run/active-environment` and shown by `./status`. Run `./down` before switching modes so processes from different environments cannot be mixed.
- DEV uses `http://localhost:8090` only and starts no Cloudflare process.
- UAT dependency handling follows `.env.uat`: loopback PostgreSQL/Redis endpoints are provisioned as isolated persistent Docker containers, while remote endpoints are DNS/TCP-preflighted before the frontend build. Local UAT containers are authenticated before backend startup and stopped by `./down` with data volumes preserved.
- UAT starts the existing `shresta-uat-api` named tunnel and forwards `https://uat-api.shrestaexclusive.com` directly to backend port `8090`.
- PROD starts the existing `shresta-prod-api` named tunnel and forwards `https://api.shrestaexclusive.com` directly to backend port `8090`.
- The named tunnel and DNS route must already exist in the Cloudflare account. Frontend and media delivery use independent origins.
- Services run through `nohup`, so they keep running when terminal closes or screen locks.
- They stop only when you run `./down`, manually kill processes, or machine shuts down.
- Requires sibling repos in the same parent folder: `SHRESTA-EXCLUSIVE-BE` and `SHRESTA-EXCLUSIVE-WEB-FE`.

## Production

Set up the env file once:

```bash
cp .env.prod.example .env.prod
# edit .env.prod and fill in all secrets
```

Then start production with a single command:

```bash
./up prod
```

The production backend launcher runs `scripts/verify-provider-readiness` before boot and fails fast when domains, the 604800-second browser cache policy, custom media URL, R2 credentials, or another provider configuration is incomplete.

The controller validates the named production tunnel before starting services, verifies local PROD health, and exposes the backend only after `https://api.shrestaexclusive.com` reports PROD readiness.

UAT RAZORPAY INDIA TEST CREDS :-
| Network    | Test card             |
| ---------- | --------------------- |
| Visa       | `4100 2800 0000 1007` |
| Mastercard | `5500 6700 0000 1002` |
| RuPay      | `6527 6589 0000 1005` |
| Amex       | `3402 560004 01007`   |


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
| `R2_ACCESS_KEY_ID` | Server-only Cloudflare R2 authentication |
| `R2_SECRET_ACCESS_KEY` | Server-only Cloudflare R2 authentication |

**Provider activation notes (credentials-only startup):**

- Razorpay Standard Checkout is active when `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` are set (or force-enabled via `SHRESTA_RAZORPAY_CHECKOUT_ENABLED=true`).
- Razorpay webhook verification is active when `SHRESTA_RAZORPAY_WEBHOOK_SECRET` or `RAZORPAY_WEBHOOK_SECRET` is set.
- SMS delivery is active when either `SHRESTA_SMS_SPRINGEDGE_API_KEY` or `SHRESTA_SMS_MSG91_AUTH_KEY` is set.
- Transactional email uses the PostgreSQL outbox and the ordered provider adapters configured under `SHRESTA_EMAIL_*`; DEV captures without external delivery.
- If provider settings are incomplete, both `./up uat` and `./up prod` exit before public tunnel readiness with actionable errors and stop any partial stack.

**Transactional email provider configuration:**

- Set `SHRESTA_EMAIL_ENABLED=true`, the verified `SHRESTA_EMAIL_SENDER`, and an ordered `SHRESTA_EMAIL_PROVIDER_ORDER` in UAT/PROD.
- Configure each provider independently with `SHRESTA_EMAIL_BREVO_API_KEY`, `SHRESTA_EMAIL_RESEND_API_KEY`, `SHRESTA_EMAIL_MAILJET_API_KEY` plus `SHRESTA_EMAIL_MAILJET_SECRET_KEY`, `SHRESTA_EMAIL_MAILERSEND_API_KEY`, and `SHRESTA_EMAIL_ELASTIC_API_KEY`.
- Configure the matching webhook secrets or Mailjet webhook Basic credentials. Brevo and Elastic Email use an unguessable webhook URL token when the provider cannot sign callbacks.
- `SHRESTA_EMAIL_CONNECT_TIMEOUT` and `SHRESTA_EMAIL_READ_TIMEOUT` must remain below `SHRESTA_EMAIL_LEASE_DURATION`. Provider `Retry-After` guidance is honored only when the retry remains within notification expiry.
- HTTP success is recorded only when the provider returns its documented message-scoped acceptance ID. Aggregate transaction IDs are not substituted for message IDs. Ambiguous read timeouts are quarantined as `OUTCOME_UNKNOWN` and are never blindly failed over.
- DEV never calls external email providers. UAT requires `SHRESTA_EMAIL_UAT_ALLOWLIST` or `SHRESTA_EMAIL_UAT_RECIPIENT_OVERRIDE`.
- Login and registration OTPs remain valid for 10 minutes. Resending occurs only after an explicit customer action, expires every prior pending code for that identity, and issues a replacement valid for 10 minutes.
- OTP issuance allows one initial request plus two explicit resends per identity in a rolling hour, with a 60-second cooldown between requests. OTP values are never returned by UAT responses.
- The Actuator `email` health component reports configured provider codes and non-secret queue age/count diagnostics; stale actionable work reports `DEGRADED`.
- After loading the target environment, run `./scripts/verify-provider-readiness` before deployment. Live verification is separate and must send exactly one message per provider to a controlled recipient only after all automated checks pass.

**Razorpay Standard Checkout API endpoints:**

- `POST /api/v1/customer/orders/draft/{draftOrderId}/razorpay-order` (authenticated; amount and currency are derived from the active customer-owned draft)
- `POST /api/v1/payments/razorpay/verify-payment`

`create-order` enforces minimum amount `100` paise and returns Razorpay `orderId`, amount, and currency. `verify-payment` validates `razorpay_signature` using HMAC-SHA256(`order_id|payment_id`) with your server-side key secret.

**SMS API modes:**

- SpringEdge latest mode: `SHRESTA_SMS_SPRINGEDGE_API_MODE=json-v1` with `SHRESTA_SMS_SPRINGEDGE_ENDPOINT=https://api.springedge.com/v1/sms/send`.
- SpringEdge legacy mode: `SHRESTA_SMS_SPRINGEDGE_API_MODE=legacy-form` with legacy form endpoint.
- MSG91 latest flow mode (default): `SHRESTA_SMS_MSG91_API_MODE=flow-v5` with `SHRESTA_SMS_MSG91_ENDPOINT=https://control.msg91.com/api/v5/flow`, `authkey` header, and `SHRESTA_SMS_MSG91_TEMPLATE_ID` configured.
- MSG91 legacy mode: `SHRESTA_SMS_MSG91_API_MODE=legacy-form` with `https://api.msg91.com/api/v2/sendsms`.

**SMS webhook authentication (recommended for production):**

- SpringEdge webhook auth: set `SHRESTA_SMS_SPRINGEDGE_WEBHOOK_AUTH_ENABLED=true`, `SHRESTA_SMS_SPRINGEDGE_WEBHOOK_AUTH_HEADER`, and `SHRESTA_SMS_SPRINGEDGE_WEBHOOK_AUTH_TOKEN`.
- MSG91 webhook auth: set `SHRESTA_SMS_MSG91_WEBHOOK_AUTH_ENABLED=true`, `SHRESTA_SMS_MSG91_WEBHOOK_AUTH_HEADER`, and `SHRESTA_SMS_MSG91_WEBHOOK_AUTH_TOKEN`.
- Optional HMAC signature verification: set `*_WEBHOOK_SIGNATURE_ENABLED=true`, `*_WEBHOOK_SIGNATURE_HEADER`, and `*_WEBHOOK_SIGNATURE_SECRET` for each provider.
- Signature comparison supports plain hex and `sha256=<hex>` header formats.
- If auth is enabled and token/header check fails, the API returns `403` with error code `SMS_WEBHOOK_UNAUTHORIZED`.

**SMS webhook ingestion endpoints:**

- `POST /api/v1/sms/providers/springedge/webhook`
- `POST /api/v1/sms/providers/msg91/webhook`

Webhook payloads are persisted to `customer_sms_webhook_events` and linked to outbound SMS rows when `provider_message_id` is available in provider callbacks.

**Manual SHRESTA admin delivery ops note:**

- `PATCH /api/v1/admin/orders/{orderNumber}/status` accepts optional `opsReference` (max 80 chars).
- When present, backend appends it as `[ops-ref: <value>]` to admin-written order/payment/fulfillment status events for timeline audit.

**Production must use only the `prod` profile.** The complete catalog/demo dataset is restricted to `local` and `dev`; UAT and PROD receive only the website/logo bootstrap.

**Key prefix isolation**: always set `SHRESTA_KV_KEY_PREFIX=shresta:prod` in production and `shresta:uat` in UAT to prevent Redis key collisions across environments.

Health check endpoint for load balancer readiness probes:

```text
GET /actuator/health/readiness   → 200 when ready
GET /actuator/health/liveness    → 200 when live
```

## DigitalOcean VPS deployment alignment

Recommended topology:

- Backend JVM process on the VPS (this app) listening on `127.0.0.1:8090`.
- Nginx (or Caddy) terminating TLS on `443` and reverse-proxying to `127.0.0.1:8090`.
- Public DNS for backend API, for example `api.your-domain.com`.

Production profile notes for reverse proxies:

- `server.forward-headers-strategy=framework` is enabled so `X-Forwarded-*` / `Forwarded` headers are honored behind DO/Nginx.
- Use `./up prod`; its internal launcher enforces `SPRING_PROFILES_ACTIVE=prod`.

Webhook URL alignment checklist (must be public HTTPS):

- Razorpay webhook: `https://api.your-domain.com/api/v1/payments/providers/razorpay/webhook`
- SpringEdge webhook: `https://api.your-domain.com/api/v1/sms/providers/springedge/webhook`
- MSG91 webhook: `https://api.your-domain.com/api/v1/sms/providers/msg91/webhook`

If SMS webhook auth/signature controls are enabled, ensure provider dashboards send matching headers and secrets.

Minimal VPS runtime checklist:

- Java 21 installed and selected.
- PostgreSQL + Redis reachable from VPS.
- `.env.prod` filled with real values from `.env.prod.example`.
- Inbound firewall open for `80/443` (and private DB/Redis networking as applicable).
- Nginx proxy timeout tuned to allow webhook/event bursts without premature termination.

## Docker

Build the image:

```bash
docker build -t shresta-be:local .
```

Run the image:

```bash
docker run --rm -p 8090:8090 --env-file .env.dev shresta-be:local
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
docker-compose --env-file .env.dev -f docker-compose.dev.yml ps
docker-compose --env-file .env.dev -f docker-compose.dev.yml logs
```

If media URLs return `404`, reseed/verify MinIO (DB seeding is separate and automatic):

```bash
./scripts/seed-local-object-storage
./scripts/verify-shresta-media-s3
./scripts/verify-no-runtime-static-media
```

If the complete catalog seed is missing after startup, confirm the controller started DEV and inspect the backend log:

```bash
./status
curl http://localhost:8090/api/v1/platform/health
./stack-control.sh logs be
```

If backend port `8090` is busy, stop the tracked stack before restarting it:

```bash
lsof -nP -iTCP:8090 -sTCP:LISTEN
./down
./up dev
```

If migrations fail, inspect the local database and migration history:

```bash
docker-compose --env-file .env.dev -f docker-compose.dev.yml exec postgres psql -U shresta_app -d shresta
```

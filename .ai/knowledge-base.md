# SHRESTA-BE AI Knowledge Base

## System Role

SHRESTA-BE is the backend commerce operating system for SHRESTA EXCLUSIVE. It owns identity, location, category configuration, catalog, inventory, Redis cart, checkout, payment, orders, logistics, notifications, admin operations, recommendations, observability, and future data-platform integration.

The backend is quick-commerce first. It is not a traditional e-commerce backend. Every customer-facing path must be inventory-aware, zone-aware, and optimized for a 10-30 minute dark-store delivery promise.

## Architecture

Phase 1 is a Java 21 Spring Boot modular monolith. It has one deployable artifact and strict module boundaries. PostgreSQL is durable truth. Redis is volatile real-time state. Spring events are Phase 1 event transport, but event contracts are designed for Kafka in Phase 2.

Phase 2 moves infrastructure to AWS ECS Fargate, RDS Multi-AZ, ElastiCache, MSK/Kafka, Typesense, CloudFront, Secrets Manager, and PostGIS.

Phase 3 moves city-scale workloads to EKS, Karpenter, KEDA, PgBouncer, ClickHouse, Flink, Feast, and Milvus.

## Module Hierarchy

- `common`: API envelopes, money, IDs, validation, errors, tracing, clocks.
- `identity`: OTP, JWT RS256, refresh tokens, customer/admin identity.
- `location`: address, pincode, city, zone, warehouse resolution.
- `category`: category family, type, attribute, filter, tax, styling configuration, and public configuration read API.
- `catalog`: product, variant, media, category assignment, SEO metadata.
- `search`: PostgreSQL FTS adapter, search documents, future Typesense adapter.
- `cart`: Redis cart and server-priced cart view.
- `checkout`: checkout session, idempotency, server pricing, reservation initiation.
- `payment`: Razorpay order creation, webhook verification, payment intent, refund, reconciliation.
- `order`: order state machine, immutable item snapshots, history, invoice metadata.
- `inventory`: warehouse stock, reservations, hard deduction, adjustments, transaction ledger.
- `logistics`: shipment, rider assignment, ETA, GPS, delivery OTP, reverse logistics.
- `notification`: outbox, push, SMS, email, templates, preferences, retry.
- `admin`: four-role ACL, change-request review queue, store scope, audit, operational APIs.
- `recommendation`: behavior events and rule-based recommendation surfaces.
- `observability`: metrics, logs, traces, alerts, runbooks, SLOs.

## API Groups

- `/api/v1/auth`: OTP request, OTP verify, refresh, logout.
- `/api/v1/users`: customer profile and saved addresses.
- `/api/v1/locations`: pincode coverage, zone availability, ETA preview.
- `/api/v1/categories`: implemented category configuration read API returning active category families, product types, attributes, filters, tax rules, and styling rules.
- `/api/v1/products`: listing, detail, media, variants, availability.
- `/api/v1/search`: search, autocomplete, facets.
- `/api/v1/cart`: get cart, add item, update quantity, remove item, clear cart.
- `/api/v1/checkout`: initiate checkout, validate coupon, price lock, reservation status.
- `/api/v1/payments`: create payment intent, refund status, reconciliation views.
- `/api/v1/webhooks/razorpay`: HMAC-verified webhook ingestion.
- `/api/v1/orders`: order detail, status, history, cancellation, invoice.
- `/api/v1/delivery`: shipment tracking, rider pickup, OTP verification.
- `/api/v1/admin`: operations APIs with coarse ACL, change-request governance, store-scoped RBAC, and audit.
- `/api/v1/platform/health`: application health contract.

## Database Design

All durable records use UUID primary keys. All money fields use BIGINT paise. All business records prefer soft delete/status transitions over hard delete. Flyway migrations are forward-only.

Core planned table ownership:

- Identity: `users`, `otp_codes`, `refresh_tokens`.
- Location: `user_addresses`, `delivery_zones`, `warehouses`.
- Category: `category_family_config`, `category_product_type_config`, `category_attribute_config`, `category_filter_config`, `category_tax_config`, `category_styling_config`.
- Catalog: `products`, `product_variants`, `product_media`, `product_attributes`.
- Search: `product_search_documents`.
- Checkout/payment: `checkout_sessions`, `payment_intents`, `payments`, `refunds`, `payment_webhook_events`.
- Order: `orders`, `order_items`, `order_status_history`.
- Inventory: `inventory`, `inventory_reservations`, `inventory_transactions`.
- Logistics: `shipments`, `delivery_assignments`, `delivery_otp_codes`, `rider_locations`.
- Notification: `notification_outbox`, `notification_templates`, `notification_preferences`.
- Admin: `admin_change_requests`, future `admin_roles`, `admin_permissions`, `admin_audit_log`.
- Recommendation/analytics: `behaviour_events`, `recommendation_snapshots`.

No Phase 1 PostgreSQL cart tables are allowed.

## Authentication and Authorization

Customers authenticate with OTP-first mobile login. Admins require stronger role-based authorization. JWT access tokens are RS256 and short-lived. Refresh tokens are rotated and stored through httpOnly secure cookies for browser clients.

Authorization is enforced at API and service levels. Admin operations use four coarse roles: `SUPER_ADMIN` has all access, `CHANGE_SUBMITTER` can submit create/update/archive/delete requests, `CHANGE_REVIEWER` can approve or reject requests, and `CHANGE_MANAGER` can both submit and review. Admin change payloads are persisted in `admin_change_requests` before reviewer approval. Approval dispatches the pending payload through `AdminChangeRequestApplier` in the same transaction before the request is marked `APPROVED`; unsupported payload/action pairs fail as structured admin change request errors.

Non-production startup may load `classpath:db/dev-uat-migration` through the `local`, `dev`, or `uat` Spring profiles. That repeatable migration seeds `uat_seed_accounts` and `uat_seed_admin_roles` with `testuser@gmail.com`, OTP `123456`, customer/admin enablement, and all four coarse admin roles. Production Flyway locations must remain limited to the core migration path unless explicitly overridden by production deployment configuration; the UAT seed path is not production-safe.

## Event Flows

Events are domain facts. Phase 1 uses after-commit Spring events. Phase 2 moves to Kafka with unchanged business event schemas.

Critical events:

- `UserAddressSaved`
- `ProductUpdated`
- `InventoryReserved`
- `InventoryReservationExpired`
- `InventoryDeducted`
- `CheckoutInitiated`
- `PaymentIntentCreated`
- `PaymentCaptured`
- `PaymentFailed`
- `OrderCreated`
- `OrderPacked`
- `OrderOutForDelivery`
- `OrderDelivered`
- `ShipmentCreated`
- `RiderAssigned`
- `NotificationRequested`
- `BehaviourEventRecorded`

Payment and order events must never publish before transaction commit.

## Business Rules

- Server computes all totals. Client amounts are ignored.
- Warehouse/zone is resolved at address save.
- Checkout validates cart, address, warehouse, inventory, price, tax, delivery fee, coupon, and idempotency before payment session creation.
- Payment webhook creates/advances order state. Frontend payment callback does not.
- Inventory uses soft reservation before payment and hard deduction after payment confirmation.
- Delivery requires 4-digit OTP verification for premium product handoff.
- Category behavior comes from configuration rows and canonical facets.
- The public category configuration API must read PostgreSQL configuration tables and must not hard-code launch category behavior in Java.
- Admin `ARCHIVE` requests are temporary removals; admin `DELETE` requests are permanent row removals and require reviewer approval before execution.

## Observability

Every critical path must emit structured logs, metrics, and trace IDs. Required dashboards include checkout, payment, orders, inventory, delivery SLA, notifications, Redis, PostgreSQL, provider health, and cost per delivered order.

Mandatory business KPIs:

- Order latency.
- Delivery SLA breach rate.
- Inventory mismatch rate.

## Testing Standards

- Unit tests for pure business rules and value objects.
- Migration lint tests for SQL seed patterns that have failed real PostgreSQL boot verification.
- Integration tests with Testcontainers for DB/Redis paths.
- Contract tests for API response shape and OpenAPI.
- Testcontainers integration tests for Flyway-backed category configuration reads.
- Concurrency tests for inventory and idempotency.
- Security tests for auth, RBAC, webhook verification, and rate limiting.
- Load tests before every production launch milestone.

## Deployment

Phase 1 uses Railway-compatible container deployment. The backend exposes health/readiness and Prometheus endpoints. Secrets are environment variables and never committed. Phase 2 migrates to AWS-managed services with blue/green or canary release gates.

The backend README is intentionally limited to repository-specific operational notes: dependencies, local environment setup, service startup, development and production commands, verification gates, and troubleshooting. Product features, architecture decisions, and roadmap notes belong in `.ai/` documents.

## CI

GitHub Actions workflow `.github/workflows/ci.yml` runs Java 21 with Gradle 8.10 and executes `gradle test --no-daemon` on pushes to `main` and pull requests.

## Local Backend Toolchain

The backend has two local toolchain layers:

- Homebrew runtime dependencies: `openjdk@21`, `gradle`, `postgresql@16`, `redis`, `docker`, `docker-compose`, and `colima`.
- Pinned workspace build toolchain under the workspace root:

- `../.tools/jdks/jdk-21.0.11+10/Contents/Home`
- `../.tools/gradle/gradle-8.10`
- `../.tools/gradle-user-home`
- `../.tools/tmp`

Use `./scripts/be-java` and `./scripts/be-gradle` from `SHRESTA-BE` so Java 21, Gradle 8.10, Gradle cache, and temp directories are pinned to the workspace-local toolchain.

Local PostgreSQL and Redis are launched with Colima plus Homebrew standalone Compose:

```bash
colima start
docker-compose -f docker-compose.dev.yml up -d
```

Flyway uses both `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` so PostgreSQL 16.x can be detected and migrated during Spring Boot startup.

The V1 category configuration seed migration qualifies every overlapping family/seed column with explicit aliases. `MigrationSqlLintTest` guards the regression class where PostgreSQL rejects ambiguous names such as `display_name` and `sort_order`.

See `.ai/adr/0004-workspace-local-backend-toolchain.md` and `.ai/adr/0005-flyway-postgresql-database-module.md` for the installation rationale and Flyway module decision.

## Implemented Category Configuration API

`GET /api/v1/categories` is implemented by:

- `CategoryConfigController`
- `CategoryConfigService`
- `CategoryConfigRepository`
- `JdbcCategoryConfigRepository`
- `CategoryFamilyResponse`

The endpoint returns active rows from `category_family_config`, `category_product_type_config`, `category_attribute_config`, `category_filter_config`, `category_tax_config`, and `category_styling_config`. It exposes canonical snake_case keys and `attribute_facets.{attribute_key}` backend mappings for frontend filter generation.

`CategoryConfigIntegrationTest` runs the API against PostgreSQL 16 through Testcontainers and verifies Flyway-seeded launch configuration from an empty schema.

Gradle test tasks default Testcontainers to `unix://$HOME/.colima/default/docker.sock` with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` and docker-java system property `api.version=1.41` when no `DOCKER_HOST` is already set and the Colima socket exists. CI or developer machines with a standard Docker socket can continue using their existing Docker environment.

## Implemented Storefront, Asset, KV, and Mutation Safety Modules

`GET /api/v1/storefront/home` returns the backend-owned customer home dataset from PostgreSQL. It is assembled by `StorefrontHomeService` from `storefront_home_sections`, `storefront_home_items`, `media_assets`, and `media_asset_variants`. The response includes CDN-compatible asset URLs, variants, LQIP data URLs, dimensions, delivery mode, and version.

Featured collection `itemCount` values are derived from active backend product-card records in the `bestsellers` section rather than presentation metadata. Family collections count matching product family keys, saree collections count all active backend product cards, and any collection can provide `metadata.productBadgeFilters` to count/include products by normalized backend badge tokens. The logic is generic for every collection and must not depend on route slug names. Product cards include customer-facing `description` from `storefront_home_items.description` and `longDescription` from `metadata.longDescription`; future product editing should move into a dedicated backend catalog/PIM surface rather than frontend-owned admin copy controls. The storefront home KV cache key is versioned as `active:v8` for the V21 reference-catalog expansion, V22 product-copy normalization, product-copy response shape, V14 product copy, and V20 customer-visible copy cleanup so stale Redis payloads are bypassed after deployment.

The V14 storefront copy migration removes internal architecture wording from customer-visible seed copy, refreshes category-family descriptions, and adds short/long product descriptions for all launch product cards across silk sarees, new arrivals, and wedding edits. Customer-facing seed text must never mention backend, API, DB/KV, S3/CDN, metadata, contracts, snapshots, or other implementation terms.

`GET /api/v1/storefront/stores` returns the backend-owned store locator and service network dataset from PostgreSQL. It is assembled by `StorefrontStoresService` from `storefront_store_sections` and `store_locations`, with KV-first reads and DB fallback. The response includes locator copy, active stores/service desks, city/state filters, service modes, contact data, coordinates, opening hours, and fulfillment promises.

The V5 storefront seed refresh aligns the API-owned home experience with the old SHRESTA reference wine/gold UI while preserving SHRESTA EXCLUSIVE as a saree brand. It expands collection/product rows, refreshes trust/hero copy, and corrects responsive variant dimensions for logo and portrait category assets. Historical migrations remain immutable for Flyway checksum safety. The V20 migration updates existing databases with customer-safe V5/V6 copy. The V21 migration loads the complete 71-product old-reference saree catalog from the canonical Kanhai mock source into PostgreSQL-owned product and media rows, adds missing saree product types for saree weave and drape variations, and keeps product image paths as S3-compatible object keys under `products/*`. The V22 migration keeps V21 seed facts intact while normalizing customer-facing product title fragments, the old `Mehndia` typo, descriptions, media alt text, and SEO copy. These changes pair with `storefront-home:active:v8` plus `storefront-stores:active:v2` cache keys.

The V8 storefront media restoration keeps required launch reference assets for silk saree and saree/festive surfaces active and READY. Storefront home reads hide archived media by design, so these seed assets must remain active for the API to return non-null `MediaAsset` objects for saree/festive hero, collection, material, and product records.

`StorefrontMediaUrlBuilder` appends backend-owned version query parameters to generated media URLs. Media rows store object keys, and the required `SHRESTA_MEDIA_ASSET_BASE_URL` maps every customer/admin image to an S3-compatible public origin such as local MinIO, AWS S3, or CloudFront. The application fails media URL construction when the public media base is missing instead of silently emitting broken relative object paths. API URL contracts must not expose backend-local `/shresta-media` or `/shresta-assets` paths.

Local/UAT object storage uses MinIO through `docker-compose.dev.yml`. Production uses the same S3-compatible code path by setting `SHRESTA_ASSET_OBJECT_ENDPOINT`, `SHRESTA_ASSET_OBJECT_BUCKET`, `SHRESTA_ASSET_OBJECT_REGION`, `SHRESTA_ASSET_OBJECT_ACCESS_KEY`, `SHRESTA_ASSET_OBJECT_SECRET_KEY`, and `SHRESTA_MEDIA_ASSET_BASE_URL` to real provider values. This is not a mock path: generated originals and variants are signed-uploaded to the configured object store, and the UI renders those public URLs. `seed/shresta-media` is a non-served local bootstrap input for MinIO only; Spring Boot no longer exposes `/shresta-media/**`.

`scripts/verify-no-runtime-static-media` enforces the media boundary. It fails if image binaries are placed under `src/`, if Spring Boot static media directories are reintroduced, or if runtime Java/config references backend-local media routes such as `/shresta-media` or `/shresta-assets`. The task is wired into Gradle `check` as `verifyNoRuntimeStaticMedia`. `seed/shresta-media` remains the only repository image-binary location and exists only to seed local/UAT/prod object storage.

Admin storefront writes live under `/api/v1/admin/storefront/home`. Section and item updates require `X-SHRESTA-ADMIN-KEY` and `Idempotency-Key`, run under a Redis mutation lock, update PostgreSQL, then publish fresh storefront KV after commit.

Admin asset APIs live under `/api/v1/admin/assets`. They support existing asset search/detail, multi-file upload, existing image replacement through `POST /api/v1/admin/assets/{assetKey}/image`, metadata update, archive/remove, and bulk category assignment. The admin DAM/PIM surface is catalog-scoped: only `category`, `product`, and `asset-manager` media assets are returned or mutated, while brand/system chrome such as the SHRESTA logo is excluded. Uploaded/replaced originals are stored under versioned object keys in `SHRESTA_ASSET_STORAGE_ROOT`, signed-uploaded to the configured S3-compatible object store, then processed into thumbnail, small, medium, and large variants; WebP/AVIF are emitted when the host tools exist. Asset tags are normalized and validated by `AssetTagRules`: uppercase token values only, 40 characters per tag, 16 tags per asset. `V19__asset_tag_contract.sql` normalizes existing `media_assets.tags` rows and adds a PostgreSQL check constraint using `shresta_valid_asset_tags(jsonb)` so direct writes cannot persist invalid tags. Asset reads are KV-first and fall back to PostgreSQL. Asset search SQL casts nullable filters to text before `IS NULL`/comparison checks so PostgreSQL can infer parameter types even when admin filters are empty. Asset response URLs use `StorefrontMediaUrlBuilder` with the configured media base URL and asset version cache-busting for originals and variants.

`V15__asset_subcategory_metadata.sql` adds `media_assets.category_product_type_key`. Admin asset upload, search, metadata update, response, and bulk assignment contracts now carry both `categoryFamilyKey` and `categoryProductTypeKey`, alongside `productSku`, tags, alt text, SEO fields, generated variants, LQIP, and versioned URLs. This is the backend-owned DAM/PIM boundary for UI-visible images: category taxonomy owns allowed families/subcategories, assets store the selected binding, and the frontend renders images from API data instead of static image lists.

`V16__s3_compatible_asset_urls_and_replacements.sql` normalizes existing media rows away from localhost backend static URLs by stripping `/shresta-media` and `/shresta-assets` prefixes back to object keys and setting `s3-compatible` storage/delivery metadata. This keeps old local rows compatible with the S3-compatible URL builder without editing already-applied Flyway migrations.

Admin category APIs live under `/api/v1/admin/catalog/categories`. They support create, update, and remove flows for category families, product types, attributes, filters, tax rules, and styling rules. Category writes refresh the active category graph in KV only after the DB transaction commits.

Admin governance APIs live under `/api/v1/admin/acl` and `/api/v1/admin/change-requests`. ACL exposes the four-role permission model. Change requests persist action, entity type/key, submitted role, reviewer role, status, and payload JSON so create/update/archive/delete work can be reviewed before destructive or catalog-changing operations are applied.

Customer auth APIs live under `/api/v1/auth/customer` and `/api/v1/customer/profile`. `V9__customer_identity_sessions.sql` creates `customer_accounts`, `customer_auth_identities`, `customer_otp_challenges`, and `customer_sessions`. Local/dev/UAT seed data remains isolated in `db/dev-uat-migration/R__seed_uat_login_accounts.sql`, which also projects the shared `testuser@gmail.com` account and verified `9876543210` mobile identity into the production identity tables only for non-production profiles. Login normalizes email/mobile identities and resolves through `customer_auth_identities`, so both identities map to the same `customerId`. Login creates a real `customer_sessions` row and stores only the SHA-256 token hash; the raw session token is returned once to the frontend proxy so it can be stored in an HTTP-only cookie. Profile reads and logout require a bearer session token and return no-store/private responses.

Customer chat APIs live under `/api/v1/customer/chat/messages`. `V10__customer_chat_support.sql` creates `customer_chat_sessions` and `customer_chat_messages` so SHRESTA Assistant conversations are persisted for audit, escalation, and future AI provider integration. The current assistant replies are deterministic intent routing for product discovery, stores, checkout, and order-support handoff; private order-specific support should require customer login before any private order data is exposed.

Customer order APIs live under `/api/v1/customer/orders`. `V11__customer_order_placement.sql` creates `customer_orders`, `customer_order_items`, and `customer_order_status_events`. `V18__customer_checkout_order_drafts.sql` adds `customer_order_drafts` and `customer_order_draft_items` for the authenticated proceed-to-checkout order ID. `POST /api/v1/customer/orders/draft` requires bearer customer auth, `Idempotency-Key`, and a per-customer Redis lock; it normalizes cart product IDs/quantities, expires old ACTIVE drafts, reuses an ACTIVE draft for the same cart signature within the 15-minute window, invalidates changed-cart ACTIVE drafts, and snapshots backend product SKU/name/family/type/media/price into draft items. Final `POST /api/v1/customer/orders` requires `draftOrderId`, validates that the draft is ACTIVE, unexpired, customer-owned, and cart-signature matched, then converts it to `CONVERTED` in the same transaction as the final order insert. `CustomerOrderController` authenticates the bearer customer session, requires `Idempotency-Key` for draft creation and placement, uses per-customer Redis mutation locks, and stores replay responses through `IdempotentMutationCoordinator`. `CustomerOrderService` loads active backend storefront product cards from PostgreSQL, never trusts browser totals/prices, snapshots product SKU/name/family/type/media/price into order items, writes initial order/payment/fulfillment status events, and returns the persisted order number/status/totals. `GET /api/v1/customer/orders` returns lightweight order summaries for the authenticated customer profile, and `GET /api/v1/customer/orders/{orderNumber}` returns only orders belonging to the authenticated customer. All order reads are filtered by `customer_orders.customer_id`.

The `kv` package provides table-configurable Redis read-through behavior. `shresta.kv.tables.<table>.enabled` and `ttl` decide whether a table may participate in KV-first reads. Aggregates declare their dependent tables, so disabling any dependency sends that aggregate directly to PostgreSQL.

The `mutation` package provides Redis-backed idempotency and locking for mutating admin APIs. Every write receives a scope, request fingerprint, lock key, response type, and mutation supplier. Successful results are stored by `Idempotency-Key`; replay with the same payload returns the stored response, and replay with a different payload returns `IDEMPOTENCY_KEY_CONFLICT`. Active locks return `MUTATION_LOCKED`.

## Developer Run Modes

- Development mode: start Colima, run `docker-compose -f docker-compose.dev.yml up -d`, then run `./scripts/be-gradle bootRun --no-daemon`.
- Production jar mode: run `./scripts/be-gradle clean bootJar --no-daemon`, then execute `build/libs/shresta-be-0.0.1-SNAPSHOT.jar` with explicit production environment variables.
- Production container mode: build `docker build -t shresta-be:local .` and run the image with database and Redis environment variables. For local container verification against Compose services, attach the app container to `shresta-be_default`.
- Verification gate: `./scripts/be-gradle test bootJar --no-daemon`, followed by runtime health checks when service dependencies are running. `./scripts/be-gradle check --no-daemon` also runs the no-runtime-static-media guard.

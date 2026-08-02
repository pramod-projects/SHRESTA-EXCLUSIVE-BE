# SHRESTA-BE AI Mind Map

```text
SHRESTA-BE
├── Common Platform
│   ├── API envelope: ApiResponse<T>
│   ├── Error model: code, message, traceId, timestamp
│   ├── Money: BIGINT paise value object
│   ├── IDs: UUID v4 value objects
│   ├── Validation: request DTO validation, no client money trust
│   └── Observability: JSON logs, traceId, Micrometer, OpenTelemetry
├── Identity Module
│   ├── APIs: /auth/otp/request, /auth/otp/verify, /auth/refresh, /auth/logout
│   ├── Services: OtpService, TokenService, RefreshTokenService
│   ├── Tables: users, otp_codes, refresh_tokens
│   ├── Non-prod seed: local/dev/uat Flyway path -> uat_seed_accounts + uat_seed_admin_roles -> testuser@gmail.com / 123456
│   ├── Redis: otp:phone:{phone}, rate:otp:{phone}
│   └── Rules: SecureRandom OTP, max 3 per phone per 10 min, JWT RS256; UAT seed path is forbidden in production
├── Location Module
│   ├── APIs: /users/addresses, /locations/coverage, /locations/eta
│   ├── Services: AddressService, ZoneResolver, WarehouseResolver
│   ├── Tables: user_addresses, delivery_zones, warehouses
│   ├── Workflow: address save -> pincode/geo validate -> zone_id and warehouse_id persisted
│   └── Phase 2: PostGIS for indexed distance queries
├── Category Module
│   ├── API: GET /api/v1/categories
│   ├── Admin API: /api/v1/admin/catalog/categories
│   ├── Controller: CategoryConfigController
│   ├── Admin controller: AdminCategoryController
│   ├── Service: CategoryConfigService
│   ├── Admin service: AdminCategoryService
│   ├── Repository interface: CategoryConfigRepository
│   ├── Repository adapter: JdbcCategoryConfigRepository
│   ├── Public contract: CategoryFamilyResponse
│   ├── Tables: category_family_config, category_product_type_config
│   ├── Tables: category_attribute_config, category_filter_config
│   ├── Tables: category_tax_config, category_styling_config
│   ├── Launch seeds: saree, silk_saree, saree
│   ├── Facets: key:value lowercase snake_case
│   ├── Backend mappings: attribute_facets.{attribute_key}
│   ├── Tests: CategoryConfigServiceTest, CategoryConfigControllerTest, CategoryConfigIntegrationTest
│   ├── Tests: AdminCategoryControllerTest
│   ├── KV: category-config:active-families depends on all category config tables
│   ├── Mutation safety: Redis idempotency + coarse category-config lock
│   └── Rule: no saree-only enums in public API contracts
├── Storefront Module
│   ├── Public API: GET /api/v1/storefront/home
│   ├── Public API: GET /api/v1/storefront/stores
│   ├── Admin API: /api/v1/admin/storefront/home
│   ├── Service: StorefrontHomeService
│   ├── Service: StorefrontStoresService
│   ├── Repository: JdbcStorefrontHomeRepository
│   ├── Repository: JdbcStorefrontStoresRepository
│   ├── Media URL builder: StorefrontMediaUrlBuilder -> S3-compatible public URLs with backend-owned v cache busting
│   ├── Static media guard: scripts/verify-no-runtime-static-media + Gradle verifyNoRuntimeStaticMedia
│   ├── Tables: storefront_home_sections, storefront_home_items
│   ├── Tables: storefront_store_sections, store_locations
│   ├── Media dependencies: media_assets, media_asset_variants
│   ├── KV: storefront-home:active:v8, storefront-stores:active:v2
│   ├── Mutation safety: Idempotency-Key + storefront-home lock
│   ├── V5 seed refresh: reference-style SHRESTA wine/gold content while preserving saree-focused SHRESTA EXCLUSIVE
│   ├── V8 seed repair: silk saree and saree/festive reference media restored to READY/active
│   ├── V14 copy refresh: customer-facing category/product copy plus ProductCard.longDescription
│   ├── V21 catalog expansion: 71 old-reference saree products seeded into DB-owned product/media rows for pagination-scale catalog validation
│   ├── V22 copy normalization: cleans old-reference product title fragments/typos and syncs media SEO/alt text
│   ├── Copy rule: storefront/store seed text uses customer SHRESTA wording and never exposes backend/API/DB/KV/S3/CDN/metadata/contract terms
│   ├── Collection counts: computed from active backend product-card rows, not itemCount metadata
│   ├── Collection filters: generic metadata.productBadgeFilters token matching for any collection, no slug-specific branching
│   └── Rule: FE home content comes from backend data, never hard-coded datasets
├── Asset Module
│   ├── Admin API: /api/v1/admin/assets
│   ├── Controller: AdminAssetController
│   ├── Service: AssetService
│   ├── Repository: JdbcAssetRepository
│   ├── Storage: AssetStorageService
│   ├── Object publisher: S3CompatibleObjectStoragePublisher
│   ├── Processor: LocalSipsAssetVariantProcessor
│   ├── Tables: media_assets, media_asset_variants
│   ├── Local object store: MinIO via docker-compose.dev.yml, using the same S3-compatible upload/read contract as production
│   ├── Seed source: seed/shresta-media only; never served by Spring Boot
│   ├── Variants: thumbnail, small, medium, large; jpg plus optional webp/avif
│   ├── Metadata: category_family_key, category_product_type_key, product_sku, tags, alt text, SEO fields
│   ├── Tag contract: AssetTagRules + V19 DB check -> uppercase token format, 40 characters per tag, 16 tags maximum
│   ├── Search SQL: nullable admin filters are explicitly cast to text for PostgreSQL type safety
│   ├── Admin scope: category, product, and asset-manager usage types only; brand/system logo assets are excluded
│   ├── Media URLs: object keys mapped through required S3-compatible public base URL plus asset version cache-busting
│   ├── Replace image: POST /admin/assets/{assetKey}/image -> preserve asset key, write vN original, regenerate variants/LQIP
│   ├── KV: asset-search and asset-detail depend on media tables
│   ├── Mutation safety: upload/update/bulk/archive idempotency + locks
│   └── Rule: original assets are stored separately from generated optimized variants and both are published to configured object storage; no runtime image binaries are allowed under src/
├── Catalog Module
│   ├── APIs: /products, /products/{id}, /admin/products
│   ├── Services: ProductService, VariantService, ProductMediaService
│   ├── Tables: products, product_variants, product_media, product_attributes
│   ├── Image rule: persist Cloudinary public_id only
│   └── Events: ProductCreated, ProductUpdated, ProductDeactivated
├── Search Module
│   ├── APIs: /search, /search/autocomplete, /search/facets
│   ├── Services: SearchService, SearchDocumentProjector
│   ├── Phase 1: PostgreSQL tsvector + pg_trgm + GIN
│   ├── Phase 2: Typesense adapter
│   └── Rule: primary search results must be inventory and zone aware
├── Cart Module
│   ├── APIs: /cart, /cart/items
│   ├── Services: CartService, CartPricingViewService
│   ├── Redis: cart:{userId}
│   ├── No PostgreSQL cart tables in Phase 1
│   └── Rules: max 10 units per variant, server-priced cart response
├── Checkout Module
│   ├── APIs: /checkout/initiate, /checkout/coupons/validate
│   ├── Services: CheckoutService, PricingService, IdempotencyService
│   ├── Tables: checkout_sessions
│   ├── Redis: checkout:lock:{userId}, idem:checkout:{key}
│   └── Workflow: cart -> address zone -> price -> reserve -> Razorpay order
├── Customer Identity Module
│   ├── API: POST /api/v1/auth/customer/login
│   ├── API: POST /api/v1/auth/customer/logout
│   ├── API: GET /api/v1/customer/profile
│   ├── Service: CustomerAuthService
│   ├── Tables: customer_accounts, customer_auth_identities, customer_otp_challenges, customer_sessions
│   ├── Non-prod seed: db/dev-uat-migration/R__seed_uat_login_accounts.sql -> testuser@gmail.com + 123456
│   ├── Session rule: raw token returned once; database stores SHA-256 hash only
│   └── Environment rule: seed login only under local/dev/uat profiles
├── Customer Chat Module
│   ├── API: POST /api/v1/customer/chat/messages
│   ├── Service: CustomerChatService
│   ├── Tables: customer_chat_sessions, customer_chat_messages
│   ├── Assistant intents: product discovery, store locator, checkout readiness, order-support handoff
│   └── Rule: anonymous exploration chat is allowed; private order support requires login handoff
├── Payment Module
│   ├── APIs: /payments, /webhooks/razorpay
│   ├── Services: RazorpayClient, WebhookService, PaymentIntentService, ReconciliationService
│   ├── Tables: payment_intents, payments, refunds, payment_webhook_events
│   ├── Redis: webhook:{razorpayPaymentId}, idem:payment:{key}
│   ├── Events: PaymentCaptured, PaymentFailed, RefundInitiated
│   └── Rules: HMAC verify, frontend callback not trusted, no card data stored
├── Order Module
│   ├── Customer API: POST /api/v1/customer/orders/draft
│   ├── Customer API: POST /api/v1/customer/orders
│   ├── Customer API: GET /api/v1/customer/orders
│   ├── Customer API: GET /api/v1/customer/orders/{orderNumber}
│   ├── Controller: CustomerOrderController
│   ├── Service: CustomerOrderService
│   ├── Contracts: CustomerOrderDraftRequest, CustomerOrderDraftResponse
│   ├── Contracts: CustomerOrderPlacementRequest, CustomerOrderResponse, CustomerOrderSummaryResponse
│   ├── Tables: customer_order_drafts, customer_order_draft_items
│   ├── Tables: customer_orders, customer_order_items, customer_order_status_events
│   ├── Draft workflow: proceed-to-checkout -> normalize cart -> expire old drafts -> reuse same-cart ACTIVE draft or invalidate changed-cart drafts -> create 15-minute order ID
│   ├── Placement safety: bearer customer session + draftOrderId + Idempotency-Key + per-customer Redis lock
│   ├── Draft placement rule: final order requires ACTIVE, unexpired, customer-owned, cart-signature-matched checkout draft and converts it to CONVERTED in the order transaction
│   ├── Pricing rule: load active backend storefront product cards and ignore browser-submitted prices
│   ├── Statuses: order_status=PLACED, payment_status=PENDING, fulfillment_status=PENDING on initial placement
│   ├── Delivery promise rule: quick-commerce minutes/hours/same-day slots; no multi-day shipping defaults
│   ├── Future APIs: /orders, /orders/{id}, /orders/{id}/cancel, invoice, payment capture advancement
│   └── Rules: customer_order_items frozen snapshots, status events append-only, customers can read only their own orders
├── Inventory Module
│   ├── APIs: /admin/inventory, internal reserve/deduct/release services
│   ├── Services: InventoryService, ReservationService, InventoryAdjustmentService
│   ├── Tables: inventory, inventory_reservations, inventory_transactions
│   ├── Redis: inventory:reserve:{reservationId}, avail_snapshot:{warehouseId}
│   ├── Events: InventoryReserved, InventoryDeducted, InventoryReleased
│   └── Rules: zero oversell, conditional updates, audit every adjustment
├── Logistics Module
│   ├── APIs: /delivery/track, /delivery/{id}/pickup-confirmed, /delivery/{id}/verify-otp
│   ├── Services: ShipmentService, RiderAssignmentService, EtaService, DeliveryOtpService
│   ├── Tables: shipments, delivery_assignments, delivery_otp_codes, rider_locations
│   ├── Redis: rider:gps:{riderId}, riders:available:{warehouseId}
│   └── Workflow: OrderCreated -> ShipmentCreated -> RiderAssigned -> PickedUp -> Delivered
├── Notification Module
│   ├── Services: NotificationOutboxService, FcmSender, SmsSender, EmailSender
│   ├── Tables: notification_outbox, notification_templates, notification_preferences
│   ├── Channels: FCM primary, SMS fallback, email receipt
│   └── Rules: async, retryable, non-blocking for checkout/order
├── Admin Module
│   ├── API: /api/v1/admin/acl/me -> four-role permission summary
│   ├── API: /api/v1/admin/change-requests -> submit/list/get/approve/reject governed requests
│   ├── Roles: SUPER_ADMIN, CHANGE_SUBMITTER, CHANGE_REVIEWER, CHANGE_MANAGER
│   ├── Services: AdminChangeRequestService, AdminChangeRequestApplier, future AdminAuditService
│   ├── Tables: admin_change_requests, future admin_roles, admin_permissions, admin_audit_log
│   ├── Apply routes: asset metadata/removal/bulk assignment, category config, storefront product merchandising
│   └── Rules: create/update/archive/delete requests are reviewed before applied; DELETE is permanent row removal
├── Recommendation Module
│   ├── APIs: /recommendations/home, /recommendations/product/{id}, /recommendations/cart
│   ├── Services: RuleBasedRecommendationService, BehaviourEventService
│   ├── Tables: behaviour_events, recommendation_snapshots
│   ├── Phase 1: rule-based and SQL co-occurrence
│   └── Phase 2: feature store, ranking model, inventory-aware ML
└── Platform Evolution
    ├── Phase 1: Railway + Spring events + PostgreSQL FTS
    ├── Phase 2: AWS ECS + Kafka + Typesense + PostGIS
    ├── Phase 3: EKS + KEDA + ClickHouse + Flink + Feast + Milvus
    ├── CI: GitHub Actions + Java 21 + Gradle 8.10 + test suite
    ├── Local build toolchain: scripts/be-java + scripts/be-gradle -> ../.tools Java 21 and Gradle 8.10
    ├── Local runtime toolchain: Homebrew openjdk@21, postgresql@16, redis, docker, docker-compose, colima
    ├── Local services: colima start -> docker-compose -f docker-compose.dev.yml up -d -> shresta-postgres + shresta-redis
    ├── Migration runtime: flyway-core + flyway-database-postgresql for PostgreSQL 16.x startup migration support
    ├── Migration guard: MigrationSqlLintTest -> V1 seed aliases + non-prod UAT seed profile isolation
    ├── Media boundary guard: verifyNoRuntimeStaticMedia -> image binaries only in seed/shresta-media, runtime images only from S3-compatible URLs
    ├── KV read-through: RedisKvReadThroughCache -> table-enabled dependencies -> DB fallback
    ├── Mutation safety: RedisIdempotentMutationCoordinator -> Idempotency-Key -> lock -> DB transaction -> after-commit KV publish
    ├── Dev runbook: README -> Colima + docker-compose + ./scripts/be-gradle bootRun --no-daemon
    ├── Prod jar runbook: README -> ./scripts/be-gradle clean bootJar --no-daemon -> ./scripts/be-java -jar build/libs/shresta-be-0.0.1-SNAPSHOT.jar
    ├── Prod container runbook: README -> Dockerfile -> docker build -> docker run with env vars and /actuator/health
    └── Testcontainers: Gradle Test -> DOCKER_HOST from Colima socket + Docker API 1.41 when present -> PostgreSQL 16 integration tests
```

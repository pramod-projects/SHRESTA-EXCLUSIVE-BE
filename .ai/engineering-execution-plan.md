# SHRESTA Backend Engineering Execution Plan

This plan expands the source architecture into backend execution phases. Each phase ends in a deployable state and must update `.ai/` documentation in the same change as source code.

## North Star

SHRESTA-BE is the transactionally correct quick-commerce core for SHRESTA EXCLUSIVE premium saree catalog. It must support silk sarees, and future product families through configuration rather than category-specific code. The backend optimizes for sub-second checkout, zero overselling, payment/order consistency, 10-30 minute delivery workflows, observability, security, and AI-readable maintainability.

## Global Engineering Rules

- Prefer long-term correctness over short-term speed.
- Keep Phase 1 operationally simple, but design every interface for Phase 2 scale.
- Do not introduce temporary architecture. A small implementation must still use the final patterns.
- Write forward-only Flyway migrations.
- Keep Redis for volatile real-time state and PostgreSQL for durable truth.
- Publish events after transaction commit.
- Add negative tests for money, inventory, payment, authorization, idempotency, and state machines.
- Treat `.ai/` files as first-class deliverables.

## Phase 0: Source Understanding and AI Foundation

Objectives:

- Read and register the source planning corpus.
- Establish mandatory AI Bank files before feature code.
- Record durable architecture decisions.
- Create the first deployable skeletal repository with health, money invariants, and migration foundations.

Scope:

- `.ai/source-register.md`, `.ai/business-rules.json`, `.ai/architecture-map.json`, `.ai/knowledge-base.md`, `.ai/mind-map.md`, `.ai/sync-policy.md`.
- ADRs for modular monolith, invariants, and infrastructure phases.
- Build configuration, application entrypoint, health endpoint, money value object, first category-config migration.

Deliverables:

- Java 21 Spring Boot repo skeleton.
- Health/readiness endpoint.
- `Money` value object enforcing paise-only arithmetic.
- Flyway V1 with category configuration tables and launch seeds.
- Docker/Railway/local dev configs.

Acceptance Criteria:

- Repository can be built with Java 21 and Gradle.
- `/actuator/health` and `/api/v1/platform/health` return healthy responses.
- Money tests prove no float/rupee API exists.
- Migration creates category config foundation without cart tables.
- AI docs describe every created module, class, table, and rule.

Testing Strategy:

- Unit tests for money arithmetic.
- Spring MVC slice test for health endpoint.
- Future Testcontainers migration test when Java 21 toolchain is available.

Production Readiness Checklist:

- No secrets committed.
- `application.yml` exposes only safe actuator endpoints.
- JSON logs and trace IDs are configured.
- DB and Redis are externalized by environment.

## Phase 1: Platform Core and Database Foundation

Objectives:

- Build the production baseline for identity, locations, category configuration, catalog, inventory, cart, checkout, payment, order, logistics, notifications, admin, recommendations, and observability modules.
- Keep the deployment as one Spring Boot artifact while enforcing module boundaries.

Scope:

- Package-level module boundaries and public service interfaces.
- Domain value objects: `UserId`, `ProductId`, `WarehouseId`, `ZoneId`, `OrderId`, `PaymentIntentId`, `PriceInPaise`, `Quantity`.
- PostgreSQL schema V1-Vn for identity, address, zone, category config, catalog, variants, media, inventory, payment intents, payments, orders, order items, order status history, shipments, delivery OTP, notifications, admin audit, and behavior events.
- Redis key conventions for cart, OTP, idempotency, inventory reservations, search hot keys, rider GPS, and rate limiting.

Dependencies:

- PostgreSQL 16 with `pgcrypto`, `pg_trgm`, and later PostGIS.
- Redis 7.
- Spring Security, Spring Data JPA, Flyway, Micrometer, OpenAPI.

Acceptance Criteria:

- All core tables use UUID primary keys and timestamp audit columns.
- All money columns are BIGINT paise with non-negative checks.
- No PostgreSQL cart table exists in Phase 1.
- Category seeds cover `saree`, `silk_saree`, and `saree`.
- OpenAPI is generated from controllers.
- `.ai/mind-map.md` includes every table and service.

Testing Strategy:

- Repository tests with Testcontainers.
- Migration smoke tests from empty DB.
- Architecture tests preventing cross-module repository access.
- Contract tests for standard API response shape.

Documentation Requirements:

- Update schema map, API contracts, domain glossary, and module ownership.
- Add ADRs for any schema or module boundary changes.

Production Readiness Checklist:

- Connection pool max 10 for Phase 1.
- Flyway checksum protection enabled.
- App DB role excludes DDL and DELETE in production.
- Actuator readiness/liveness enabled.

## Phase 2: Identity, Location, Category, Catalog, and Search

Objectives:

- Build customer/admin identity and the location-aware catalog browsing path.
- Ensure all catalog/search behavior is category-config driven.

Scope:

- OTP request/verify flow with Redis rate limiting and SecureRandom OTPs.
- JWT RS256 access tokens and httpOnly refresh token rotation.
- Address APIs that resolve delivery zone and warehouse at save time.
- Category configuration admin APIs.
- Product, variant, media, attribute, tax, and styling APIs.
- PostgreSQL FTS search with inventory-aware filtering.
- Cloudinary public_id validation and URL generation service.

Acceptance Criteria:

- Customer can authenticate by OTP and save an address with zone/warehouse pre-resolution.
- Product APIs expose canonical `attribute_facets` strings, not Java enum casing.
- Search returns only deliverable/in-stock primary results for the selected zone.
- Cloudinary full URLs are never persisted.
- Admin role permissions are enforced and audited.

Testing Strategy:

- OTP abuse/rate-limit tests.
- JWT expiry and refresh rotation tests.
- Address zone resolution tests.
- Catalog category-config tests for saree, silk saree, and saree.
- Search ranking and filter tests with pg_trgm/tsvector.

Documentation Requirements:

- Update API contracts, auth flow, category rules, search flow, and component-to-table mapping.

Production Readiness Checklist:

- OTP max 3 per phone per 10 minutes.
- CORS whitelist configured.
- OpenAPI examples include paise and facets.
- Admin audit log is append-only.

## Phase 3: Cart, Checkout, Payment, Order, and Inventory Consistency

Objectives:

- Build the revenue critical path with zero oversell and zero payment/order mismatch.

Scope:

- Redis-primary cart APIs with item and quantity limits.
- Checkout initiation with server-side pricing, category tax, delivery fee, coupon hooks, idempotency, and inventory soft reservation.
- Razorpay order creation.
- Razorpay webhook ingestion with HMAC verification and Redis NX idempotency.
- Payment intent lifecycle.
- Atomic order creation after webhook confirmation.
- Hard inventory deduction with PostgreSQL conditional updates/locks.
- Immutable order item snapshots.
- Order state machine and cancellation rules.
- Payment expiry scheduler and inventory reservation release.
- Reconciliation job shell with audit trail.

Acceptance Criteria:

- Same checkout request with same idempotency key cannot create duplicate payment/order records.
- Payment success but order failure is reconciled and alerted.
- Last-unit concurrent checkout cannot oversell.
- Order is created only after webhook verification.
- Order total is always server-computed.
- Cart is cleared only after successful order creation.

Testing Strategy:

- Concurrency tests for inventory reservation/deduction.
- Webhook replay tests.
- Payment amount tampering tests.
- Transaction rollback tests.
- State machine transition tests.
- Scheduler tests for expiry release.

Documentation Requirements:

- Update payment rules, idempotency rules, order flow, inventory rules, event map, and mind map.

Production Readiness Checklist:

- Razorpay secrets only in environment.
- HMAC verification constant-time.
- 3-layer idempotency active.
- Prometheus metrics for checkout, payment, order, and inventory paths.

## Phase 4: Logistics, Notifications, and Admin Operations

Objectives:

- Complete the 10-30 minute delivery operating system.
- Give operations teams safe real-time controls.

Scope:

- Shipment creation from order events.
- Rider availability, assignment scoring, attempt policy, ETA calculation, and secure delivery OTP.
- Rider GPS Redis TTL updates.
- Delivery state machine: pending assignment, assigned, accepted, picked up, arriving, delivered, failed.
- FCM, SMS, and email notification outbox.
- Notification templates, preferences, retries, and fallback routing.
- Admin dashboard APIs for order ops, inventory ops, catalog ops, logistics ops, finance, customer support, reports, and audit logs.

Acceptance Criteria:

- Order confirmation triggers shipment and rider assignment asynchronously after commit.
- Delivery OTP is SecureRandom, single-use, and timing-safe.
- Admin actions are store-scoped and audit logged.
- Failed rider assignment escalates to admin after configured attempts.
- Customer receives order lifecycle notifications without blocking order creation.

Testing Strategy:

- Assignment algorithm unit tests.
- Delivery OTP verification tests.
- Notification retry/fallback tests.
- Admin RBAC and audit tests.
- End-to-end order-to-delivery simulation.

Documentation Requirements:

- Update shipping rules, notification flow, admin permission map, incident runbooks, and event schemas.

Production Readiness Checklist:

- FCM, MSG91, and SES circuit breakers.
- Delivery SLA alerts.
- Rider GPS TTL and stale-location handling.
- Admin audit immutability verified.

## Phase 5: Observability, Security, Reliability, and Performance Hardening

Objectives:

- Make the Phase 1 platform launch-safe under real users.

Scope:

- Structured JSON logging with trace IDs.
- Micrometer metrics for business and technical SLOs.
- Prometheus scrape endpoint and Grafana dashboard definitions.
- OpenTelemetry tracing conventions.
- Resilience4j circuit breakers for external dependencies.
- Global exception handling with no stack traces.
- OWASP protections, CSP guidance, request validation, rate limiting, security headers.
- k6/Gatling load profiles for browse, cart, checkout, webhook, admin.
- Runbooks for payment failure, inventory mismatch, delivery SLA breach, Redis degradation, DB latency, and external provider outage.

Acceptance Criteria:

- Critical path SLOs have metrics, alerts, and runbooks.
- Security scan gates are defined in CI.
- Failure modes degrade gracefully without corrupting money/order/inventory.
- Load tests hit launch traffic targets with p95 below defined budgets.

Testing Strategy:

- Security unit and integration tests.
- Rate limit tests.
- Circuit breaker tests.
- Load and smoke tests.
- Chaos experiments documented before Phase 2.

Documentation Requirements:

- Update observability rules, security rules, reliability rules, and production checklist.

Production Readiness Checklist:

- Alerts route to on-call channel.
- Backup and restore procedure documented.
- Database slow query threshold configured.
- Redis, DB, payment, and notification degradation paths verified.

## Phase 6: Phase 2 Scale Migration

Objectives:

- Move from launch architecture to growth architecture without rewriting domains.

Scope:

- AWS ECS Fargate in ap-south-1.
- RDS Multi-AZ PostgreSQL and ElastiCache Redis.
- Kafka/MSK replacing in-process event transport while preserving event contracts.
- Typesense replacing PostgreSQL FTS for search.
- CloudFront CDN and AWS Secrets Manager.
- PostGIS for indexed geospatial rider and warehouse queries.
- Blue/green or canary deployments.

Acceptance Criteria:

- Event publisher interface supports Spring and Kafka transports.
- Search interface supports PostgreSQL and Typesense adapters.
- Data migration is rehearsed with zero-downtime rollback plan.
- Multi-AZ failover is tested.
- CPDO target is tracked.

Testing Strategy:

- Contract tests across event transport adapters.
- Search parity tests.
- Migration rehearsals.
- Load test at 2,000+ orders/day profile.
- Failure injection for DB/Redis/provider outages.

Documentation Requirements:

- Add ADRs for migration decisions.
- Update infra, event, search, cost, and reliability docs.

Production Readiness Checklist:

- Secrets in AWS Secrets Manager.
- RDS backups and PITR enabled.
- Kafka lag alerts and DLQ policy active.
- Typesense sync and reindex runbook documented.

## Phase 7: Phase 3 City-Scale Platform

Objectives:

- Scale to many cities while preserving city-level isolation and operational clarity.

Scope:

- EKS with city namespaces, Karpenter, KEDA, service mesh, NetworkPolicy.
- PgBouncer and partitioning for high order volume.
- ClickHouse for admin analytics.
- Flink streaming jobs for inventory, ETA, demand, and recommendation features.
- Feast feature store and Milvus vector search for ML recommendations.
- Selective extraction of ETA and recommendation services.
- Chaos engineering in CI/CD.

Acceptance Criteria:

- City outage cannot corrupt another city's order flow.
- Kafka consumer lag scales down automatically under burst.
- Analytics workloads do not impact OLTP DB.
- Recommendation API returns in under 50 ms with inventory filtering.
- Error budget burn alerts block unsafe deployments.

Testing Strategy:

- Multi-city routing tests.
- KEDA scale tests.
- Data pipeline replay tests.
- Chaos tests for pod kill, provider latency, Redis degradation, dark-store outage, and flash sale load.

Documentation Requirements:

- Update city topology, data platform, ML registry, FinOps, runbooks, and mind maps.

Production Readiness Checklist:

- Per-city dashboards.
- Cost allocation per service and city.
- SLO/error-budget governance.
- Disaster recovery RPO/RTO validated.

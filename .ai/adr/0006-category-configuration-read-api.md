# ADR 0006: Category Configuration Read API

## Status

Accepted.

## Context

SHRESTA must support silk sarees, and future families through configuration rather than category-specific source code. The V1 schema already seeds category families, product types, attributes, filters, tax configuration, and styling configuration.

Frontend discovery, catalog, search, recommendations, and admin operations need a stable read contract before product and search modules can be implemented safely.

## Decision

Expose `GET /api/v1/categories` as a public category configuration read API. The endpoint uses a category application service and a PostgreSQL JDBC read-model repository to assemble active category configuration rows into a stable API contract.

The API returns canonical snake_case keys, active product types, attributes, filters, GST basis-point tax configuration, and styling rules. It does not use Java enums for launch categories.

## Consequences

- Frontend and future generated OpenAPI types can depend on one category configuration contract.
- Category behavior remains data-driven and extendable by migrations/admin flows.
- Testcontainers now verifies the category API against a real PostgreSQL 16 database with Flyway migrations from an empty schema.
- Future category admin mutations must preserve this public read contract or introduce versioned API changes.

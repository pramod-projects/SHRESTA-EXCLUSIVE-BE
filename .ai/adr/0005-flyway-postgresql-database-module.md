# ADR 0005: Flyway PostgreSQL Database Module

## Status

Accepted.

## Context

The backend targets PostgreSQL 16 as durable truth and uses Flyway for forward-only schema migrations. During local boot verification against `postgres:16-alpine`, Spring Boot connected successfully but Flyway failed with `Unsupported Database: PostgreSQL 16.14`.

Flyway's database-specific support is modular, so relying on `flyway-core` alone is insufficient for the SHRESTA backend runtime.

## Decision

Keep `org.flywaydb:flyway-core` and add `org.flywaydb:flyway-database-postgresql` as an implementation dependency.

## Consequences

- Spring Boot startup can detect PostgreSQL 16.x and execute Flyway migrations.
- Local Colima/PostgreSQL verification becomes a required backend readiness check.
- Future PostgreSQL major upgrades must include Flyway compatibility verification before changing the Docker image, cloud database version, or CI integration-test matrix.

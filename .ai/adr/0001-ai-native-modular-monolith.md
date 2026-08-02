# ADR 0001: AI-Native Modular Monolith First

## Status

Accepted

## Context

The source architecture requires SHRESTA to launch as a production-grade quick-commerce system while initially remaining operable by a small team or single developer. The system must still scale toward city-level isolation, Kafka-backed event streams, and selective service extraction.

## Decision

SHRESTA-BE starts as a Java 21 Spring Boot modular monolith with strict package/module boundaries, stable domain events, and repository AI documentation from day one. Microservices are deferred until a domain needs independent scaling, a different runtime, or a larger team ownership boundary.

## Consequences

- Phase 1 keeps ACID transactions simple for checkout, payment, order, and inventory.
- Event contracts are designed now so Kafka can replace in-process events without changing business semantics.
- Module boundary tests and `.ai-context.json` files are mandatory to prevent the monolith from becoming tangled.
- Delivery ETA and recommendation engines can later become Python services without rewriting the commerce core.

# ADR 0003: Phase 1 Infrastructure and Scale Path

## Status

Accepted

## Decision

Phase 1 targets Railway.app with PostgreSQL 16, Redis 7, one Spring Boot service, Flyway migrations, Spring events, PostgreSQL full-text search, and managed environment variables. Phase 2 moves to AWS ap-south-1 with ECS Fargate, RDS Multi-AZ, ElastiCache, Kafka/MSK, Typesense, CloudFront, and Secrets Manager. Phase 3 moves to EKS, Karpenter, KEDA, PgBouncer, ClickHouse, Flink, Feast, and Milvus when order volume and city expansion justify the operational cost.

## Phase Triggers

- Move to Phase 2 when p99 API latency exceeds 500 ms, daily orders exceed 2,000, or operational risk requires Multi-AZ.
- Move to Phase 3 when daily orders exceed 15,000, cities exceed five, or independent scaling becomes mandatory.

## Consequences

Every Phase 1 implementation must have a direct Phase 2 migration path. Do not add infrastructure that becomes a dead end.

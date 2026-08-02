# ADR 0008: KV-First Read-Through and Mutation Safety

## Status

Accepted.

## Context

Storefront, category, and asset reads must be fast, but PostgreSQL remains durable truth. Administrators also need safe update APIs that avoid duplicate writes, accidental overwrite races, and stale cache publication.

The user explicitly required KV-first reads, per-table configurability, updates through KV, locking, and idempotency handling for APIs.

## Decision

Introduce `KvReadThroughCache` as a table-configurable Redis read-through layer. Aggregates declare their dependent tables. If global KV or any dependent table is disabled, the aggregate reads directly from PostgreSQL. Redis value keys include per-table version keys, and writes publish fresh snapshots or invalidate dependent table versions after transaction commit.

Introduce `IdempotentMutationCoordinator` as the Redis-backed mutation safety boundary. Mutating admin APIs supply a scope, submitted `Idempotency-Key`, request fingerprint, lock key, response type, and mutation supplier. The coordinator replays identical completed requests, rejects reused keys with different payloads, and uses Redis locks to prevent concurrent protected writes.

## Consequences

- KV is a performance layer, not durable truth.
- Per-table KV toggles can isolate problematic tables without disabling all caching.
- Admin writes fail closed when Redis mutation safety is unavailable.
- Backend services must refresh/invalidate KV after database commit, never before.

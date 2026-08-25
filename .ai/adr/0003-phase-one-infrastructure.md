# ADR 0003: Phase 1 Infrastructure and Scale Path

## Status

Accepted

## Decision

Phase 1 uses Vercel for Next.js, a VPS for one Spring Boot service, PostgreSQL 16, Redis 7, Cloudflare, and Cloudflare R2. Infrastructure changes must preserve direct browser-to-R2 canonical uploads and may be introduced only from measured operational need.

## Phase Triggers

- Move to Phase 2 when p99 API latency exceeds 500 ms, daily orders exceed 2,000, or operational risk requires Multi-AZ.
- Move to Phase 3 when daily orders exceed 15,000, cities exceed five, or independent scaling becomes mandatory.

## Consequences

Every Phase 1 implementation must have a direct Phase 2 migration path. Do not add infrastructure that becomes a dead end.

# ADR 0007: Direct Cloudflare R2 Canonical Media

## Status

Accepted.

## Context

SHRESTA storefront and admin surfaces need high-quality media without routing file bytes through Next.js or Spring Boot. Cloudflare R2 is the only production object store. DEV uses MinIO solely as an R2 protocol-compatible local target.

## Decision

Spring validates each request, creates a UUID-based immutable key, persists `PENDING_UPLOAD`, and returns a short-lived presigned PUT URL. The browser uploads exactly one canonical object directly to R2 and then requests completion. Spring performs `HEAD` verification before changing the row to `READY`.

PostgreSQL stores metadata and object keys only. R2 stores no thumbnails, responsive sizes, WebP/AVIF derivatives, LQIP objects, or replacement objects at stable paths. Primary-image replacement links the product to a newly uploaded READY asset and removes the old object only through explicit lifecycle approval.

## Consequences

- Frontend API responses stay metadata-first; image bytes use the configured Cloudflare custom media domain.
- Cloudflare Image Resizing and Next.js image transformation are disabled; frontend layout and CSS control display dimensions while loading the canonical object directly from the custom media domain.
- Immutable object keys make query-string cache busting unnecessary.
- Asset changes invalidate media KV tables and refresh storefront home KV only after commit.
- UAT browser cache is environment-configured to 30 days; PROD is environment-configured to 7 days.

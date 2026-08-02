# ADR 0007: Asset Management Media Pipeline

## Status

Accepted.

## Context

SHRESTA storefront and admin surfaces need high-quality images without sending heavy API payloads or forcing the frontend to own asset datasets. Administrators must upload, search, update, archive, and organize assets while the customer storefront receives fast CDN-compatible media metadata.

The Phase 1 implementation must expose S3-compatible media URLs from the backend. Local and UAT use MinIO as a real S3-compatible object store; production uses AWS S3, CloudFront-backed S3, or another compatible provider through environment variables.

## Decision

Store original uploads separately from generated variants under versioned object keys such as `assets/{assetKey}/v{version}/original/...` and `assets/{assetKey}/v{version}/variants/...`. Persist asset records in `media_assets` and variant records in `media_asset_variants`. Generate thumbnail, small, medium, and large variants locally, emit WebP/AVIF variants when host tools exist, and store LQIP data URLs for fast perceived rendering.

Expose admin asset APIs under `/api/v1/admin/assets` for search/detail, multi-file upload, existing image replacement, metadata update, bulk category assignment, and archive/remove. Backend responses include asset keys, backend-versioned/cache-busted S3-compatible URLs, dimensions, byte sizes, status, alt text, tags, SEO fields, variants, and optimization statistics.

## Consequences

- Frontend API responses stay metadata-first; image bytes are served from immutable cacheable URLs whose backend-generated versions bust stale caches.
- Database rows store object keys and delivery metadata; `StorefrontMediaUrlBuilder` maps them to the configured S3/CloudFront media base URL.
- Replacing an existing image preserves the asset key, bumps the version, regenerates variants, and lets versioned URLs invalidate stale caches.
- Asset changes invalidate media KV tables and refresh storefront home KV only after commit.
- Future hard purge must remain a separate guarded operation from archive/remove.

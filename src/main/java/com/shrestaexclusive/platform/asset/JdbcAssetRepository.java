package com.shrestaexclusive.platform.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaUrlBuilder;

@Repository
class JdbcAssetRepository implements AssetRepository {

    private record SystemMediaCounts(
            long total,
            long imageTotal,
            long videoTotal,
            long otherTotal,
            long referencedTotal,
            long unreferencedTotal
    ) {
    }

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final List<String> ADMIN_MANAGED_USAGE_TYPES = List.of("category", "product", "asset-manager");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StorefrontMediaUrlBuilder mediaUrlBuilder;

    @Autowired
    @SuppressWarnings("unused")
    JdbcAssetRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            StorefrontMediaUrlBuilder mediaUrlBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mediaUrlBuilder = mediaUrlBuilder;
    }

    @Override
    public boolean productMediaTargetExists(String productId, String actor) {
        List<UUID> products = jdbcTemplate.queryForList("""
            SELECT item.id
            FROM storefront_home_items item
            JOIN storefront_home_sections section ON section.id = item.section_id
            WHERE item.item_key = :productId
              AND section.section_key = 'bestsellers'
              AND item.is_active = TRUE
            FOR UPDATE OF item
            """, new MapSqlParameterSource("productId", productId), UUID.class);
        if (!products.isEmpty()) {
            return true;
        }
        List<UUID> reservations = jdbcTemplate.queryForList("""
            SELECT id
                FROM product_media_reservations
                WHERE product_id = :productId
                  AND reserved_by = :actor
                  AND status = 'ACTIVE'
                  AND expires_at > now()
            FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
            .addValue("actor", actor), UUID.class);
        return !reservations.isEmpty();
    }

    @Override
    public void insertProductMediaReservation(UUID reservationId, String productId, String actor, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO product_media_reservations (id, product_id, reserved_by, expires_at)
                VALUES (:id, :productId, :actor, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("id", reservationId)
                .addValue("productId", productId)
                .addValue("actor", actor)
                .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
    }

    @Override
    public void consumeProductMediaReservation(String productId) {
        jdbcTemplate.update("""
                UPDATE product_media_reservations
                SET status = 'CONSUMED', consumed_at = now()
                WHERE product_id = :productId AND status IN ('ACTIVE', 'SUBMITTED')
                """, new MapSqlParameterSource("productId", productId));
    }

        @Override
        public boolean activeProductMediaReservationExists(String productId) {
                List<UUID> reservations = jdbcTemplate.queryForList("""
                                SELECT id
                                FROM product_media_reservations
                                WHERE product_id = :productId
                                    AND status = 'ACTIVE'
                                    AND expires_at > now()
                                FOR UPDATE
                                """, new MapSqlParameterSource("productId", productId), UUID.class);
                return !reservations.isEmpty();
        }

        @Override
        public boolean readyProductMediaMatches(String productId, UUID mediaId, String assetKey, String mediaType) {
                List<UUID> media = jdbcTemplate.queryForList("""
                                SELECT id
                                FROM media_assets
                                WHERE id = :mediaId
                                    AND asset_key = :assetKey
                                    AND product_sku = :productId
                                    AND media_type = :mediaType
                                    AND status = 'READY'
                                    AND is_active = TRUE
                                FOR UPDATE
                                """, new MapSqlParameterSource()
                                .addValue("mediaId", mediaId)
                                .addValue("assetKey", assetKey)
                                .addValue("productId", productId)
                                .addValue("mediaType", mediaType), UUID.class);
                return !media.isEmpty();
        }

            @Override
            public boolean readyDisplayMediaMatches(UUID mediaId, String assetKey, String mediaType, String actor) {
                List<UUID> media = jdbcTemplate.queryForList("""
                        SELECT id
                        FROM media_assets
                        WHERE id = :mediaId
                            AND asset_key = :assetKey
                            AND media_type = :mediaType
                            AND status = 'READY'
                            AND is_active = TRUE
                            AND (CAST(:actor AS text) IS NULL OR uploaded_by = :actor)
                        FOR UPDATE
                        """, new MapSqlParameterSource()
                        .addValue("mediaId", mediaId)
                        .addValue("assetKey", assetKey)
                        .addValue("mediaType", mediaType)
                        .addValue("actor", actor), UUID.class);
                return !media.isEmpty();
            }

        @Override
        public List<String> findProductMediaAssetKeys(String productId) {
                return jdbcTemplate.queryForList("""
                                SELECT media.asset_key
                                FROM media_assets media
                                WHERE media.product_sku = :productId
                                    AND media.media_type IN ('PRODUCT_IMAGE', 'PRODUCT_VIDEO')
                                    AND media.is_active = TRUE
                                    AND NOT EXISTS (
                                            SELECT 1 FROM admin_change_requests request
                                            WHERE request.status = 'PENDING_REVIEW'
                                                AND request.request_type = 'storefront-product-media-link'
                                                AND request.payload ->> 'assetKey' = media.asset_key
                                    )
                                """, new MapSqlParameterSource("productId", productId), String.class);
        }

                @Override
                public List<String> findAbandonedDisplayAssetKeys(Instant createdBefore, int limit) {
                                return jdbcTemplate.queryForList("""
                                                                SELECT media.asset_key
                                                                FROM media_assets media
                                                                WHERE media.media_type IN ('DISPLAY_IMAGE', 'DISPLAY_VIDEO')
                                                                    AND media.status = 'READY'
                                                                    AND media.is_active = TRUE
                                                                    AND media.created_at < :createdBefore
                                                                    AND NOT EXISTS (
                                                                            SELECT 1 FROM storefront_home_items item
                                                                            WHERE item.media_asset_id = media.id OR item.video_media_asset_id = media.id
                                                                    )
                                                                    AND NOT EXISTS (
                                                                            SELECT 1 FROM admin_change_requests request
                                                                            WHERE request.status = 'PENDING_REVIEW'
                                                                                AND request.request_type IN (
                                                                                    'storefront-display-media',
                                                                                    'storefront-display-image',
                                                                                    'storefront-display-video'
                                                                                )
                                                                                AND (request.payload ->> 'imageAssetKey' = media.asset_key
                                                                                         OR request.payload ->> 'videoAssetKey' = media.asset_key)
                                                                    )
                                                                    AND NOT EXISTS (
                                                                            SELECT 1 FROM admin_change_requests request
                                                                            WHERE request.status = 'PENDING_REVIEW'
                                                                                AND request.request_type = 'storefront-product-media-link'
                                                                                AND request.payload ->> 'assetKey' = media.asset_key
                                                                    )
                                                                ORDER BY media.created_at
                                                                LIMIT :limit
                                                                FOR UPDATE SKIP LOCKED
                                                                """, new MapSqlParameterSource()
                                                                .addValue("createdBefore", Timestamp.from(createdBefore))
                                                                .addValue("limit", limit), String.class);
                }

    @Override
    public long countProductMedia(String productId, String mediaType) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets media
                WHERE media.product_sku = :productId
                  AND media.media_type = :mediaType
                  AND media.is_active = TRUE
                  AND (media.status = 'READY'
                       OR (media.status = 'PENDING_UPLOAD' AND media.upload_expires_at > now()))
                  AND NOT EXISTS (
                      SELECT 1 FROM storefront_home_items item
                      WHERE item.media_asset_id = media.id OR item.video_media_asset_id = media.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM storefront_home_item_gallery gallery
                      WHERE gallery.media_asset_id = media.id AND gallery.is_active = TRUE
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM admin_change_requests request
                      WHERE request.status = 'PENDING_REVIEW'
                        AND (
                            request.payload ->> 'newAssetKey' = media.asset_key
                            OR request.payload ->> 'galleryAssetKey' = media.asset_key
                            OR request.payload ->> 'demoVideoAssetKey' = media.asset_key
                            OR request.payload ->> 'mediaAssetKey' = media.asset_key
                            OR jsonb_exists(COALESCE(request.payload -> 'galleryAssetKeys', '[]'::jsonb), media.asset_key)
                        )
                  )
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("mediaType", mediaType), Long.class);
        return count == null ? 0 : count;
    }

        @Override
        public boolean submittedProductMediaReservationExists(String productId) {
                List<UUID> reservations = jdbcTemplate.queryForList("""
                                SELECT id
                                FROM product_media_reservations
                                WHERE product_id = :productId AND status = 'SUBMITTED'
                                FOR UPDATE
                                """, new MapSqlParameterSource("productId", productId), UUID.class);
                return !reservations.isEmpty();
        }

        @Override
        public boolean submitProductMediaReservation(String productId, String actor) {
                return jdbcTemplate.update("""
                                UPDATE product_media_reservations
                                SET status = 'SUBMITTED'
                                WHERE product_id = :productId
                                    AND reserved_by = :actor
                                    AND status = 'ACTIVE'
                                    AND expires_at > now()
                                """, new MapSqlParameterSource()
                                .addValue("productId", productId)
                                .addValue("actor", actor)) == 1;
        }

    @Override
    public MediaUploadRecord insertPendingUpload(
            UUID assetId,
            String assetKey,
            String objectKey,
            MediaUploadAuthorizationRequest request,
            String actor,
            Instant expiresAt
    ) {
        Integer widthPx = request.widthPx();
        Integer heightPx = request.heightPx();
        jdbcTemplate.update("""
                INSERT INTO media_assets (
                    id, asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                    usage_type, storage_provider, storage_key, product_sku, content_type,
                    byte_size, status, media_type, upload_expires_at, uploaded_by, original_filename
                ) VALUES (
                    :id, :assetKey, :objectKey, :altText, :widthPx, :heightPx, 'cloudflare-r2',
                    'asset-manager', 'cloudflare-r2', :objectKey, :productId, :contentType,
                    :byteSize, 'PENDING_UPLOAD', :mediaType, :expiresAt, :actor, :originalFilename
                )
                """, new MapSqlParameterSource()
                .addValue("id", assetId)
                .addValue("assetKey", assetKey)
                .addValue("objectKey", objectKey)
                .addValue("altText", firstText(request.altText(), request.originalFilename()))
                .addValue("widthPx", widthPx == null ? Integer.valueOf(1) : widthPx)
                .addValue("heightPx", heightPx == null ? Integer.valueOf(1) : heightPx)
                .addValue("productId", emptyToNull(request.productId()))
                .addValue("contentType", request.contentType())
                .addValue("byteSize", request.byteSize())
                .addValue("mediaType", request.mediaType())
                .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .addValue("actor", emptyToNull(actor))
                .addValue("originalFilename", request.originalFilename()));
        return new MediaUploadRecord(assetId, assetId.toString(), assetKey, objectKey, request.productId(),
                request.mediaType(), request.contentType(), request.byteSize(), expiresAt, "PENDING_UPLOAD");
    }

    @Override
    public List<String> findExpiredActiveProductReservations(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT product_id
                FROM product_media_reservations
                WHERE status = 'ACTIVE' AND expires_at <= now()
                ORDER BY expires_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("limit", limit), String.class);
    }

    @Override
    public void expireProductMediaReservation(String productId) {
        jdbcTemplate.update("""
                UPDATE product_media_reservations
                SET status = 'EXPIRED'
                WHERE product_id = :productId AND status = 'ACTIVE'
                """, new MapSqlParameterSource("productId", productId));
    }

    @Override
    public MediaUploadRecord pendingUploadForUpdate(String mediaId, String actor) {
        UUID id;
        try {
            id = UUID.fromString(mediaId);
        } catch (IllegalArgumentException exception) {
            throw new AssetNotFoundException(mediaId);
        }
        List<MediaUploadRecord> uploads = jdbcTemplate.query("""
                SELECT id, asset_key, storage_key, product_sku, media_type, content_type,
                       byte_size, upload_expires_at, status
                FROM media_assets
                                WHERE id = :id
                                    AND uploaded_by = :actor
                                    AND is_active = TRUE
                FOR UPDATE
                                """, new MapSqlParameterSource()
                                .addValue("id", id)
                                .addValue("actor", actor), (rs, rowNum) -> new MediaUploadRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("id", UUID.class).toString(),
                rs.getString("asset_key"),
                rs.getString("storage_key"),
                rs.getString("product_sku"),
                rs.getString("media_type"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getObject("upload_expires_at", OffsetDateTime.class).toInstant(),
                rs.getString("status")
        ));
        if (uploads.isEmpty()) {
            throw new AssetNotFoundException(mediaId);
        }
        return uploads.getFirst();
    }

    @Override
    public void completeUpload(UUID assetId, String etag) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET status = 'READY', object_etag = :etag, processing_error = NULL, updated_at = now()
                WHERE id = :assetId AND status = 'PENDING_UPLOAD'
                """, new MapSqlParameterSource()
                .addValue("assetId", assetId)
                .addValue("etag", etag));
    }

    @Override
    public AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size) {
        MapSqlParameterSource parameters = searchParameters(query, categoryFamilyKey, categoryProductTypeKey, productSku, status)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<AssetBaseRow> assets = jdbcTemplate.query("""
                SELECT id, asset_key, original_filename, asset_url, alt_text, category_family_key,
                       category_product_type_key, product_sku, status, version, width_px, height_px, byte_size, content_type,
                       delivery_mode, tags, seo_title, seo_description
                FROM media_assets
                WHERE is_active = TRUE
                                    AND status = 'READY'
                  AND usage_type IN (:adminManagedUsageTypes)
                                    AND (
                                                EXISTS (
                                                        SELECT 1 FROM storefront_home_items item
                                                        WHERE item.media_asset_id = media_assets.id
                                                             OR item.video_media_asset_id = media_assets.id
                                                )
                                                OR EXISTS (
                                                        SELECT 1 FROM storefront_home_item_gallery gallery
                                                        WHERE gallery.media_asset_id = media_assets.id
                                                            AND gallery.is_active = TRUE
                                                )
                                    )
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                  AND (CAST(:categoryFamilyKey AS text) IS NULL OR category_family_key = CAST(:categoryFamilyKey AS text))
                  AND (CAST(:categoryProductTypeKey AS text) IS NULL OR category_product_type_key = CAST(:categoryProductTypeKey AS text))
                  AND (CAST(:productSku AS text) IS NULL OR product_sku = CAST(:productSku AS text))
                  AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                ORDER BY updated_at DESC, asset_key
                LIMIT :limit OFFSET :offset
                """, parameters, this::assetBaseRow);

        long total = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                WHERE is_active = TRUE
                                    AND status = 'READY'
                  AND usage_type IN (:adminManagedUsageTypes)
                                    AND (
                                                EXISTS (
                                                        SELECT 1 FROM storefront_home_items item
                                                        WHERE item.media_asset_id = media_assets.id
                                                             OR item.video_media_asset_id = media_assets.id
                                                )
                                                OR EXISTS (
                                                        SELECT 1 FROM storefront_home_item_gallery gallery
                                                        WHERE gallery.media_asset_id = media_assets.id
                                                            AND gallery.is_active = TRUE
                                                )
                                    )
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                  AND (CAST(:categoryFamilyKey AS text) IS NULL OR category_family_key = CAST(:categoryFamilyKey AS text))
                  AND (CAST(:categoryProductTypeKey AS text) IS NULL OR category_product_type_key = CAST(:categoryProductTypeKey AS text))
                  AND (CAST(:productSku AS text) IS NULL OR product_sku = CAST(:productSku AS text))
                  AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                """, parameters, Long.class), "Approved media count query returned no row");

        SystemMediaCounts systemCounts = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                WITH active_ready_media AS (
                    SELECT media.asset_key,
                          CASE
                             WHEN media.media_type IN ('PRODUCT_IMAGE', 'DISPLAY_IMAGE')
                                 OR media.content_type LIKE 'image/%' THEN 'IMAGE'
                             WHEN media.media_type IN ('PRODUCT_VIDEO', 'DISPLAY_VIDEO')
                                 OR media.content_type LIKE 'video/%' THEN 'VIDEO'
                             ELSE 'OTHER'
                          END AS media_kind,
                           EXISTS (
                               SELECT 1
                               FROM storefront_home_items item
                               WHERE item.media_asset_id = media.id
                                  OR item.video_media_asset_id = media.id
                           ) OR EXISTS (
                               SELECT 1
                               FROM storefront_home_item_gallery gallery
                               WHERE gallery.media_asset_id = media.id
                                 AND gallery.is_active = TRUE
                           ) AS is_referenced
                    FROM media_assets media
                    WHERE media.is_active = TRUE
                      AND media.status = 'READY'
                )
                SELECT count(DISTINCT asset_key) AS total,
                       count(DISTINCT asset_key) FILTER (WHERE media_kind = 'IMAGE') AS image_total,
                       count(DISTINCT asset_key) FILTER (WHERE media_kind = 'VIDEO') AS video_total,
                       count(DISTINCT asset_key) FILTER (WHERE media_kind = 'OTHER') AS other_total,
                       count(DISTINCT asset_key) FILTER (WHERE is_referenced) AS referenced_total,
                       count(DISTINCT asset_key) FILTER (WHERE NOT is_referenced) AS unreferenced_total
                FROM active_ready_media
                """, new MapSqlParameterSource(), (rs, rowNum) -> new SystemMediaCounts(
                        rs.getLong("total"),
                        rs.getLong("image_total"),
                        rs.getLong("video_total"),
                        rs.getLong("other_total"),
                        rs.getLong("referenced_total"),
                        rs.getLong("unreferenced_total")
                    )), "System media count query returned no row");

        return new AssetSearchResponse(
                toResponses(assets), page, size, total,
                systemCounts.total(), systemCounts.imageTotal(), systemCounts.videoTotal(),
                systemCounts.otherTotal(), systemCounts.referencedTotal(), systemCounts.unreferencedTotal()
        );
    }

    @Override
    public Optional<AssetResponse> findByAssetKey(String assetKey) {
        List<AssetBaseRow> assets = jdbcTemplate.query("""
                SELECT id, asset_key, original_filename, asset_url, alt_text, category_family_key,
                       category_product_type_key, product_sku, status, version, width_px, height_px, byte_size, content_type,
                       delivery_mode, tags, seo_title, seo_description
                FROM media_assets
                WHERE asset_key = :assetKey
                  AND is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                """, adminManagedAssetKey(assetKey), this::assetBaseRow);

        if (assets.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(toResponses(assets).getFirst());
    }

    @Override
    public Optional<String> findContentTypeByAssetKey(String assetKey) {
        return jdbcTemplate.queryForList("""
                SELECT content_type
                FROM media_assets
                WHERE asset_key = :assetKey
                  AND is_active = TRUE
                """, new MapSqlParameterSource("assetKey", assetKey), String.class).stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public void markFailed(UUID assetId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET status = 'FAILED', processing_error = :errorMessage, updated_at = now()
                WHERE id = :assetId
                """, new MapSqlParameterSource()
                .addValue("assetId", assetId)
                .addValue("errorMessage", errorMessage));
    }

    @Override
    public void updateMetadata(String assetKey, AssetMetadataUpdateRequest request) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET alt_text = COALESCE(:altText, alt_text),
                    category_family_key = CASE
                        WHEN :clearCategoryFamilyKey = TRUE THEN NULL
                        ELSE COALESCE(:categoryFamilyKey, category_family_key)
                    END,
                    category_product_type_key = CASE
                        WHEN :clearCategoryProductTypeKey = TRUE THEN NULL
                        ELSE COALESCE(:categoryProductTypeKey, category_product_type_key)
                    END,
                    product_sku = CASE
                        WHEN :clearProductSku = TRUE THEN NULL
                        ELSE COALESCE(:productSku, product_sku)
                    END,
                    tags = CASE
                        WHEN :clearTags = TRUE THEN '[]'::jsonb
                        ELSE COALESCE(CAST(:tagsJson AS jsonb), tags)
                    END,
                    seo_title = CASE
                        WHEN :clearSeoTitle = TRUE THEN NULL
                        ELSE COALESCE(:seoTitle, seo_title)
                    END,
                    seo_description = CASE
                        WHEN :clearSeoDescription = TRUE THEN NULL
                        ELSE COALESCE(:seoDescription, seo_description)
                    END,
                    updated_at = now()
                WHERE asset_key = :assetKey
                  AND is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                """, new MapSqlParameterSource()
                .addValue("assetKey", assetKey)
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES)
                .addValue("altText", emptyToNull(request.altText()))
                .addValue("categoryFamilyKey", emptyToNull(request.categoryFamilyKey()))
                .addValue("categoryProductTypeKey", emptyToNull(request.categoryProductTypeKey()))
                .addValue("productSku", emptyToNull(request.productSku()))
                .addValue("tagsJson", request.tags() == null ? null : json(request.tags()))
                .addValue("seoTitle", emptyToNull(request.seoTitle()))
                .addValue("seoDescription", emptyToNull(request.seoDescription()))
                .addValue("clearCategoryFamilyKey", Boolean.TRUE.equals(request.clearCategoryFamilyKey()))
                .addValue("clearCategoryProductTypeKey", Boolean.TRUE.equals(request.clearCategoryProductTypeKey()))
                .addValue("clearProductSku", Boolean.TRUE.equals(request.clearProductSku()))
                .addValue("clearTags", Boolean.TRUE.equals(request.clearTags()))
                .addValue("clearSeoTitle", Boolean.TRUE.equals(request.clearSeoTitle()))
                .addValue("clearSeoDescription", Boolean.TRUE.equals(request.clearSeoDescription())));
    }

    @Override
    public void archive(String assetKey) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET status = 'ARCHIVED', is_active = FALSE, archived_at = now(), updated_at = now()
                WHERE asset_key = :assetKey
                  AND usage_type IN (:adminManagedUsageTypes)
                """, adminManagedAssetKey(assetKey));
    }

    @Override
    public List<String> findStorageKeysByAssetKey(String assetKey) {
        return jdbcTemplate.queryForList("""
                                SELECT storage_key
                                FROM media_assets
                                WHERE asset_key = :assetKey AND storage_key IS NOT NULL
                """,
                new MapSqlParameterSource("assetKey", assetKey),
                String.class);
    }

        @Override
        public boolean isReferencedByStorefront(String assetKey) {
                Boolean referenced = jdbcTemplate.queryForObject("""
                                SELECT EXISTS (
                                        SELECT 1
                                        FROM media_assets media
                                        WHERE media.asset_key = :assetKey
                                            AND (
                                                EXISTS (SELECT 1 FROM storefront_home_items item
                                                                 WHERE item.media_asset_id = media.id OR item.video_media_asset_id = media.id)
                                                OR EXISTS (SELECT 1 FROM storefront_home_item_gallery gallery
                                                                     WHERE gallery.media_asset_id = media.id AND gallery.is_active = TRUE)
                                            )
                                )
                                """, new MapSqlParameterSource("assetKey", assetKey), Boolean.class);
                return Boolean.TRUE.equals(referenced);
        }

    private static final String STOREFRONT_REFERENCE_PREDICATE = """
            EXISTS (
                SELECT 1 FROM storefront_home_items item
                WHERE item.media_asset_id = media_assets.id
                   OR item.video_media_asset_id = media_assets.id
            )
            OR EXISTS (
                SELECT 1 FROM storefront_home_item_gallery gallery
                WHERE gallery.media_asset_id = media_assets.id
                  AND gallery.is_active = TRUE
            )
            """;

    @Override
    public StorefrontUnreferencedAssetsResponse searchStorefrontUnreferenced(String query, String status, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", StringUtils.hasText(query) ? "%" + query.trim() + "%" : null)
                .addValue("status", emptyToNull(status))
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<AssetBaseRow> assets = jdbcTemplate.query("""
                SELECT id, asset_key, original_filename, asset_url, alt_text, category_family_key,
                       category_product_type_key, product_sku, status, version, width_px, height_px, byte_size, content_type,
                       delivery_mode, tags, seo_title, seo_description
                FROM media_assets
                WHERE is_active = TRUE
                  AND status = COALESCE(CAST(:status AS text), 'READY')
                  AND usage_type IN (:adminManagedUsageTypes)
                  AND NOT (""" + STOREFRONT_REFERENCE_PREDICATE + """
                  )
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                ORDER BY updated_at DESC, asset_key
                LIMIT :limit OFFSET :offset
                """, parameters, this::assetBaseRow);

        long total = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                WHERE is_active = TRUE
                  AND status = COALESCE(CAST(:status AS text), 'READY')
                  AND usage_type IN (:adminManagedUsageTypes)
                  AND NOT (""" + STOREFRONT_REFERENCE_PREDICATE + """
                  )
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                """, parameters, Long.class), "Unreferenced media count query returned no row");

        return new StorefrontUnreferencedAssetsResponse(toResponses(assets), page, size, total);
    }

    @Override
    public boolean storefrontProductMediaLinkEligible(String itemKey, String assetKey, String mediaType) {
        Boolean eligible = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM storefront_home_items item
                    JOIN storefront_home_sections section ON section.id = item.section_id,
                         media_assets media
                    WHERE item.item_key = :itemKey
                      AND item.is_active = TRUE
                      AND section.section_key = 'bestsellers'
                      AND media.asset_key = :assetKey
                      AND media.status = 'READY'
                      AND media.is_active = TRUE
                      AND (
                          (media.product_sku = :itemKey AND media.media_type = :mediaType)
                          OR (
                              NOT EXISTS (
                                  SELECT 1 FROM storefront_home_items ref
                                  WHERE ref.media_asset_id = media.id OR ref.video_media_asset_id = media.id
                              )
                              AND NOT EXISTS (
                                  SELECT 1 FROM storefront_home_item_gallery gallery
                                  WHERE gallery.media_asset_id = media.id AND gallery.is_active = TRUE
                              )
                          )
                      )
                )
                """, new MapSqlParameterSource()
                .addValue("itemKey", itemKey)
                .addValue("assetKey", assetKey)
                .addValue("mediaType", mediaType), Boolean.class);
        return Boolean.TRUE.equals(eligible);
    }

    @Override
    public boolean reassignStorefrontProductMedia(String itemKey, String assetKey, String mediaType) {
        return jdbcTemplate.update("""
                UPDATE media_assets media
                SET product_sku = :itemKey,
                    media_type = :mediaType,
                    updated_at = now()
                WHERE media.asset_key = :assetKey
                  AND media.status = 'READY'
                  AND media.is_active = TRUE
                  AND (
                      (media.product_sku = :itemKey AND media.media_type = :mediaType)
                      OR (
                          NOT EXISTS (
                              SELECT 1 FROM storefront_home_items ref
                              WHERE ref.media_asset_id = media.id OR ref.video_media_asset_id = media.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM storefront_home_item_gallery gallery
                              WHERE gallery.media_asset_id = media.id AND gallery.is_active = TRUE
                          )
                      )
                  )
                """, new MapSqlParameterSource()
                .addValue("itemKey", itemKey)
                .addValue("assetKey", assetKey)
                .addValue("mediaType", mediaType)) == 1;
    }

    @Override
    public void deletePermanently(String assetKey) {
        MapSqlParameterSource parameters = adminManagedAssetKey(assetKey);
        jdbcTemplate.update("""
                DELETE FROM storefront_home_item_gallery gallery
                USING media_assets media
                WHERE gallery.media_asset_id = media.id
                  AND gallery.is_active = FALSE
                  AND media.asset_key = :assetKey
                  AND media.usage_type IN (:adminManagedUsageTypes)
                """, parameters);
        jdbcTemplate.update("""
                UPDATE storefront_home_items item
                SET media_asset_id = NULL, updated_at = now()
                FROM media_assets media
                WHERE item.media_asset_id = media.id
                  AND media.asset_key = :assetKey
                  AND media.usage_type IN (:adminManagedUsageTypes)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM media_assets
                WHERE asset_key = :assetKey
                  AND usage_type IN (:adminManagedUsageTypes)
                """, parameters);
    }

    @Override
    public void bulkAssignCategory(List<String> assetKeys, String categoryFamilyKey, String categoryProductTypeKey) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET category_family_key = :categoryFamilyKey,
                    category_product_type_key = COALESCE(:categoryProductTypeKey, category_product_type_key),
                    updated_at = now()
                WHERE asset_key IN (:assetKeys)
                  AND is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                """, new MapSqlParameterSource()
                .addValue("assetKeys", assetKeys)
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES)
                .addValue("categoryFamilyKey", categoryFamilyKey)
                .addValue("categoryProductTypeKey", emptyToNull(categoryProductTypeKey)));
    }

    private List<AssetResponse> toResponses(List<AssetBaseRow> assets) {
        return assets.stream()
            .map(asset -> new AssetResponse(
                            asset.assetKey(),
                            asset.originalFilename(),
                    mediaUrlBuilder.assetUrl(asset.assetUrl()),
                            asset.altText(),
                            asset.categoryFamilyKey(),
                            asset.categoryProductTypeKey(),
                            asset.productSku(),
                            asset.status(),
                            asset.version(),
                            asset.widthPx(),
                            asset.heightPx(),
                            asset.byteSize(),
                            asset.contentType(),
                            asset.deliveryMode(),
                            asset.tags(),
                            asset.seoTitle(),
                                asset.seoDescription()
                            ))
                .toList();
    }

    private MapSqlParameterSource searchParameters(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status) {
        return new MapSqlParameterSource()
                .addValue("query", StringUtils.hasText(query) ? "%" + query.trim() + "%" : null)
                .addValue("categoryFamilyKey", emptyToNull(categoryFamilyKey))
                .addValue("categoryProductTypeKey", emptyToNull(categoryProductTypeKey))
                .addValue("productSku", emptyToNull(productSku))
                .addValue("status", emptyToNull(status))
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES);
    }

    private MapSqlParameterSource adminManagedAssetKey(String assetKey) {
        return new MapSqlParameterSource()
                .addValue("assetKey", assetKey)
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES);
    }

    private AssetBaseRow assetBaseRow(ResultSet rs, int rowNum) throws SQLException {
        if (rowNum < 0) {
            throw new IllegalArgumentException("JDBC row index cannot be negative");
        }
        return new AssetBaseRow(
                rs.getObject("id", UUID.class),
                rs.getString("asset_key"),
                rs.getString("original_filename"),
                rs.getString("asset_url"),
                rs.getString("alt_text"),
                rs.getString("category_family_key"),
                rs.getString("category_product_type_key"),
                rs.getString("product_sku"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getInt("width_px"),
                rs.getInt("height_px"),
                rs.getLong("byte_size"),
                rs.getString("content_type"),
                rs.getString("delivery_mode"),
                jsonStringList(rs, "tags"),
                rs.getString("seo_title"),
                rs.getString("seo_description")
        );
    }

    private List<String> jsonStringList(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid JSON string list in column " + column, exception);
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid asset tags", exception);
        }
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate.trim() : fallback;
    }

    private record AssetBaseRow(
            UUID id,
            String assetKey,
            String originalFilename,
            String assetUrl,
            String altText,
            String categoryFamilyKey,
            String categoryProductTypeKey,
            String productSku,
            String status,
            int version,
            int widthPx,
            int heightPx,
            long byteSize,
            String contentType,
            String deliveryMode,
            List<String> tags,
            String seoTitle,
            String seoDescription
    ) {
    }
}

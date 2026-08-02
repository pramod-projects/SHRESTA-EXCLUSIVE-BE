package com.shrestaexclusive.platform.asset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaUrlBuilder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
class JdbcAssetRepository implements AssetRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final List<String> ADMIN_MANAGED_USAGE_TYPES = List.of("category", "product", "asset-manager");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StorefrontMediaUrlBuilder mediaUrlBuilder;

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
    public AssetSearchResponse search(String query, String categoryFamilyKey, String categoryProductTypeKey, String productSku, String status, int page, int size) {
        MapSqlParameterSource parameters = searchParameters(query, categoryFamilyKey, categoryProductTypeKey, productSku, status)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<AssetBaseRow> assets = jdbcTemplate.query("""
                SELECT id, asset_key, original_filename, asset_url, alt_text, category_family_key,
                       category_product_type_key, product_sku, status, version, width_px, height_px, byte_size, content_type,
                       delivery_mode, lqip_data_url, tags, seo_title, seo_description
                FROM media_assets
                WHERE is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                  AND (CAST(:categoryFamilyKey AS text) IS NULL OR category_family_key = CAST(:categoryFamilyKey AS text))
                  AND (CAST(:categoryProductTypeKey AS text) IS NULL OR category_product_type_key = CAST(:categoryProductTypeKey AS text))
                  AND (CAST(:productSku AS text) IS NULL OR product_sku = CAST(:productSku AS text))
                  AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                ORDER BY updated_at DESC, asset_key
                LIMIT :limit OFFSET :offset
                """, parameters, this::assetBaseRow);

        long total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM media_assets
                WHERE is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                  AND (CAST(:query AS text) IS NULL OR asset_key ILIKE CAST(:query AS text) OR alt_text ILIKE CAST(:query AS text) OR original_filename ILIKE CAST(:query AS text))
                  AND (CAST(:categoryFamilyKey AS text) IS NULL OR category_family_key = CAST(:categoryFamilyKey AS text))
                  AND (CAST(:categoryProductTypeKey AS text) IS NULL OR category_product_type_key = CAST(:categoryProductTypeKey AS text))
                  AND (CAST(:productSku AS text) IS NULL OR product_sku = CAST(:productSku AS text))
                  AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                """, parameters, Long.class);

        return new AssetSearchResponse(toResponses(assets), page, size, total);
    }

    @Override
    public Optional<AssetResponse> findByAssetKey(String assetKey) {
        List<AssetBaseRow> assets = jdbcTemplate.query("""
                SELECT id, asset_key, original_filename, asset_url, alt_text, category_family_key,
                       category_product_type_key, product_sku, status, version, width_px, height_px, byte_size, content_type,
                       delivery_mode, lqip_data_url, tags, seo_title, seo_description
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
    public UUID insertUploadedAsset(StoredAsset storedAsset, AssetUploadRequest request) {
        UUID assetId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_assets (
                    id, asset_key, original_filename, asset_url, alt_text, width_px, height_px,
                    delivery_mode, usage_type, storage_provider, storage_key, category_family_key,
                    category_product_type_key, product_sku, content_type, byte_size, checksum_sha256, status, tags,
                    seo_title, seo_description
                )
                VALUES (
                    :id, :assetKey, :originalFilename, :assetUrl, :altText, :widthPx, :heightPx,
                    :deliveryMode, 'asset-manager', :storageProvider, :storageKey, :categoryFamilyKey,
                    :categoryProductTypeKey, :productSku, :contentType, :byteSize, :checksumSha256, 'PROCESSING', CAST(:tagsJson AS jsonb),
                    :seoTitle, :seoDescription
                )
                """, new MapSqlParameterSource()
                .addValue("id", assetId)
                .addValue("assetKey", storedAsset.assetKey())
                .addValue("originalFilename", storedAsset.originalFilename())
                .addValue("assetUrl", storedAsset.assetUrl())
                .addValue("deliveryMode", storedAsset.deliveryMode())
                .addValue("storageProvider", storedAsset.storageProvider())
                .addValue("altText", firstText(request.altText(), storedAsset.originalFilename()))
                .addValue("widthPx", storedAsset.widthPx())
                .addValue("heightPx", storedAsset.heightPx())
                .addValue("storageKey", storedAsset.storageKey())
                .addValue("categoryFamilyKey", emptyToNull(request.categoryFamilyKey()))
                .addValue("categoryProductTypeKey", emptyToNull(request.categoryProductTypeKey()))
                .addValue("productSku", emptyToNull(request.productSku()))
                .addValue("contentType", storedAsset.contentType())
                .addValue("byteSize", storedAsset.byteSize())
                .addValue("checksumSha256", storedAsset.checksumSha256())
                .addValue("tagsJson", json(request.tags() == null ? List.of() : request.tags()))
                .addValue("seoTitle", emptyToNull(request.seoTitle()))
                .addValue("seoDescription", emptyToNull(request.seoDescription())));

        return assetId;
    }

    @Override
    public AssetReplacementTarget replacementTargetForUpdate(String assetKey) {
        List<AssetReplacementTarget> targets = jdbcTemplate.query("""
                SELECT id, version + 1 AS next_version
                FROM media_assets
                WHERE asset_key = :assetKey
                  AND is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                FOR UPDATE
                """, adminManagedAssetKey(assetKey), (rs, rowNum) -> new AssetReplacementTarget(
                rs.getObject("id", UUID.class),
                rs.getInt("next_version")
        ));

        if (targets.isEmpty()) {
            throw new AssetNotFoundException(assetKey);
        }
        return targets.getFirst();
    }

    @Override
    public void replaceOriginal(UUID assetId, StoredAsset storedAsset) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET original_filename = :originalFilename,
                    asset_url = :assetUrl,
                    alt_text = COALESCE(NULLIF(alt_text, ''), :altText),
                    width_px = :widthPx,
                    height_px = :heightPx,
                    delivery_mode = :deliveryMode,
                    storage_provider = :storageProvider,
                    storage_key = :storageKey,
                    content_type = :contentType,
                    byte_size = :byteSize,
                    checksum_sha256 = :checksumSha256,
                    status = 'PROCESSING',
                    processing_error = NULL,
                    version = :version,
                    updated_at = now()
                WHERE id = :assetId
                  AND is_active = TRUE
                  AND usage_type IN (:adminManagedUsageTypes)
                """, new MapSqlParameterSource()
                .addValue("assetId", assetId)
                .addValue("adminManagedUsageTypes", ADMIN_MANAGED_USAGE_TYPES)
                .addValue("originalFilename", storedAsset.originalFilename())
                .addValue("assetUrl", storedAsset.assetUrl())
                .addValue("altText", storedAsset.originalFilename())
                .addValue("widthPx", storedAsset.widthPx())
                .addValue("heightPx", storedAsset.heightPx())
                .addValue("deliveryMode", storedAsset.deliveryMode())
                .addValue("storageProvider", storedAsset.storageProvider())
                .addValue("storageKey", storedAsset.storageKey())
                .addValue("contentType", storedAsset.contentType())
                .addValue("byteSize", storedAsset.byteSize())
                .addValue("checksumSha256", storedAsset.checksumSha256())
                .addValue("version", storedAsset.version()));
    }

    @Override
    public void replaceVariants(UUID assetId, List<GeneratedVariant> variants, String lqipDataUrl) {
        jdbcTemplate.update("""
                DELETE FROM media_asset_variants
                WHERE asset_id = :assetId
                """, new MapSqlParameterSource("assetId", assetId));

        for (GeneratedVariant variant : variants) {
            jdbcTemplate.update("""
                    INSERT INTO media_asset_variants (
                        asset_id, variant_key, format, width_px, height_px, byte_size,
                        storage_key, url_path, content_type
                    )
                    VALUES (
                        :assetId, :variantKey, :format, :widthPx, :heightPx, :byteSize,
                        :storageKey, :urlPath, :contentType
                    )
                    """, new MapSqlParameterSource()
                    .addValue("assetId", assetId)
                    .addValue("variantKey", variant.variantKey())
                    .addValue("format", variant.format())
                    .addValue("widthPx", variant.widthPx())
                    .addValue("heightPx", variant.heightPx())
                    .addValue("byteSize", variant.byteSize())
                    .addValue("storageKey", variant.storageKey())
                    .addValue("urlPath", variant.urlPath())
                    .addValue("contentType", variant.contentType()));
        }

        jdbcTemplate.update("""
                UPDATE media_assets
                SET lqip_data_url = COALESCE(:lqipDataUrl, lqip_data_url), updated_at = now()
                WHERE id = :assetId
                """, new MapSqlParameterSource()
                .addValue("assetId", assetId)
                .addValue("lqipDataUrl", lqipDataUrl));
    }

    @Override
    public void markReady(UUID assetId) {
        jdbcTemplate.update("""
                UPDATE media_assets
                SET status = 'READY', processing_error = NULL, updated_at = now()
                WHERE id = :assetId
                """, new MapSqlParameterSource("assetId", assetId));
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
                SELECT ma.storage_key
                FROM media_assets ma
                WHERE ma.asset_key = :assetKey
                  AND ma.storage_key IS NOT NULL
                UNION ALL
                SELECT mav.storage_key
                FROM media_asset_variants mav
                JOIN media_assets ma ON mav.asset_id = ma.id
                WHERE ma.asset_key = :assetKey
                  AND mav.storage_key IS NOT NULL
                """,
                new MapSqlParameterSource("assetKey", assetKey),
                String.class);
    }

    @Override
    public void deletePermanently(String assetKey) {
        MapSqlParameterSource parameters = adminManagedAssetKey(assetKey);
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
        Map<UUID, List<AssetVariantResponse>> variantsByAsset = variantsByAsset(assets.stream()
                .map(AssetBaseRow::id)
                .toList());

        return assets.stream()
                .map(asset -> {
                    List<AssetVariantResponse> variants = variantsByAsset.getOrDefault(asset.id(), List.of());
                    return new AssetResponse(
                            asset.assetKey(),
                            asset.originalFilename(),
                            mediaUrlBuilder.assetUrl(asset.assetUrl(), asset.version()),
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
                            asset.lqipDataUrl(),
                            asset.tags(),
                            asset.seoTitle(),
                            asset.seoDescription(),
                            variants,
                            stats(asset.byteSize(), variants)
                    );
                })
                .toList();
    }

    private Map<UUID, List<AssetVariantResponse>> variantsByAsset(List<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.query("""
                SELECT variant.asset_id, variant.variant_key, variant.format, variant.width_px, variant.height_px,
                       variant.byte_size, variant.url_path, asset.version
                FROM media_asset_variants variant
                JOIN media_assets asset ON asset.id = variant.asset_id
                WHERE variant.asset_id IN (:assetIds) AND variant.is_active = TRUE
                ORDER BY asset_id, width_px, format
                """, new MapSqlParameterSource("assetIds", assetIds), (rs, rowNum) -> Map.entry(
                rs.getObject("asset_id", UUID.class),
                new AssetVariantResponse(
                        rs.getString("variant_key"),
                        rs.getString("format"),
                        rs.getInt("width_px"),
                        rs.getInt("height_px"),
                        rs.getLong("byte_size"),
                        mediaUrlBuilder.assetUrl(rs.getString("url_path"), rs.getInt("version"))
                )
        )).stream().collect(Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
        ));
    }

    private AssetOptimizationStats stats(long originalBytes, List<AssetVariantResponse> variants) {
        long smallest = variants.stream()
                .filter(variant -> variant.byteSize() > 0)
                .mapToLong(AssetVariantResponse::byteSize)
                .min()
                .orElse(0L);
        long saved = smallest == 0L ? 0L : Math.max(0L, originalBytes - smallest);
        int percent = originalBytes == 0L ? 0 : (int) Math.round(saved * 100.0D / originalBytes);
        return new AssetOptimizationStats(originalBytes, smallest, saved, percent, variants.size());
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
                rs.getString("lqip_data_url"),
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
            String lqipDataUrl,
            List<String> tags,
            String seoTitle,
            String seoDescription
    ) {
    }
}

package com.shrestaexclusive.platform.storefront.home;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.GalleryRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.ItemRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.MediaRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.media.StorefrontMediaUrlBuilder;

@Repository
@SuppressWarnings({"unused", "java:S1144"})
public class JdbcStorefrontHomeRepository implements StorefrontHomeRepository {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StorefrontMediaUrlBuilder mediaUrlBuilder;

    public JdbcStorefrontHomeRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            StorefrontMediaUrlBuilder mediaUrlBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mediaUrlBuilder = mediaUrlBuilder;
    }

    @Override
    public List<SectionRow> findActiveSections() {
        return jdbcTemplate.query("""
                SELECT id, section_key, section_type, eyebrow, title, description, sort_order, metadata
                FROM storefront_home_sections
                WHERE is_active = TRUE
                ORDER BY sort_order, section_key
                """, (rs, rowNum) -> new SectionRow(
                uuid(rs, "id"),
                rs.getString("section_key"),
                rs.getString("section_type"),
                rs.getString("eyebrow"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getInt("sort_order"),
                jsonObject(rs, "metadata")
        ));
    }

    @Override
    public List<ItemRow> findActiveItems(List<String> sectionKeys) {
        return findItems(sectionKeys, false);
    }

    @Override
    public List<ItemRow> findAdminItems(List<String> sectionKeys) {
        return findItems(sectionKeys, true);
    }

    private List<ItemRow> findItems(List<String> sectionKeys, boolean includeImageLessProducts) {
        if (sectionKeys.isEmpty()) {
            return List.of();
        }

        List<ItemRow> items = jdbcTemplate.query("""
                SELECT item.id, section.section_key, item.item_key, item.family_key, item.title,
                       item.subtitle, item.description, item.cta_label, item.cta_href,
                       item.sort_order, item.is_featured, item.metadata, item.demo_video_url,
                       video.storage_key AS demo_video_storage_key,
                       media.asset_key, media.asset_url, media.alt_text, media.width_px,
                       media.height_px, media.delivery_mode, media.version, media.tags
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                LEFT JOIN media_assets media ON media.id = item.media_asset_id AND media.is_active = TRUE AND media.status = 'READY'
                LEFT JOIN media_assets video ON video.id = item.video_media_asset_id AND video.is_active = TRUE AND video.status = 'READY'
                WHERE item.is_active = TRUE AND section.section_key IN (:sectionKeys)
                                    AND (:includeImageLessProducts = TRUE OR section.section_key <> 'bestsellers' OR media.id IS NOT NULL)
                ORDER BY section.sort_order, item.sort_order, item.title
                                """, new MapSqlParameterSource()
                                .addValue("sectionKeys", sectionKeys)
                                .addValue("includeImageLessProducts", includeImageLessProducts), (rs, rowNum) -> new ItemRow(
                uuid(rs, "id"),
                rs.getString("section_key"),
                rs.getString("item_key"),
                rs.getString("family_key"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("description"),
                rs.getString("cta_label"),
                rs.getString("cta_href"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_featured"),
                jsonObject(rs, "metadata"),
                media(rs),
                videoUrl(rs.getString("demo_video_storage_key"), rs.getString("demo_video_url"))
        ));

        return items;
    }

    @Override
    public Map<UUID, List<GalleryRow>> findGalleryByItemIds(List<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        List<GalleryRow> rows = jdbcTemplate.query("""
                SELECT gallery.item_id, gallery.sort_order,
                       media.asset_key, media.asset_url, media.alt_text, media.width_px,
                      media.height_px, media.delivery_mode, media.version, media.tags
                FROM storefront_home_item_gallery gallery
                JOIN media_assets media ON media.id = gallery.media_asset_id AND media.is_active = TRUE AND media.status = 'READY'
                WHERE gallery.item_id IN (:itemIds) AND gallery.is_active = TRUE
                ORDER BY gallery.item_id, gallery.sort_order
                """, new MapSqlParameterSource("itemIds", itemIds), (rs, rowNum) -> new GalleryRow(
                uuid(rs, "item_id"),
                rs.getInt("sort_order"),
                new MediaRow(
                        rs.getString("asset_key"),
                        rs.getString("asset_url"),
                        rs.getString("alt_text"),
                        rs.getInt("width_px"),
                        rs.getInt("height_px"),
                        rs.getString("delivery_mode"),
                        rs.getInt("version"),
                        jsonStringList(rs, "tags")
                )
        ));

        Map<UUID, List<GalleryRow>> galleryByItemId = new LinkedHashMap<>();
        for (GalleryRow row : rows) {
            galleryByItemId
                    .computeIfAbsent(row.itemId(), id -> new ArrayList<>())
                        .add(row);
        }
        return galleryByItemId;
    }

    @Override
    public void updateSection(StorefrontHomeSectionUpdateCommand command) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sectionKey", command.sectionKey())
                .addValue("eyebrow", command.eyebrow())
                .addValue("title", command.title())
                .addValue("description", command.description())
                .addValue("sortOrder", command.sortOrder())
                .addValue("metadataJson", json(command.metadata()));

        int updatedSections = jdbcTemplate.update("""
                UPDATE storefront_home_sections
                SET eyebrow = COALESCE(:eyebrow, eyebrow),
                    title = COALESCE(:title, title),
                    description = COALESCE(:description, description),
                    sort_order = COALESCE(:sortOrder, sort_order),
                    metadata = COALESCE(CAST(:metadataJson AS jsonb), metadata),
                    updated_at = now()
                WHERE section_key = :sectionKey AND is_active = TRUE
                """, parameters);

        if (updatedSections == 0) {
            throw new StorefrontHomeSectionNotFoundException(command.sectionKey());
        }
    }

    @Override
    public void updateItem(StorefrontHomeItemUpdateCommand command) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("itemKey", command.itemKey())
                .addValue("familyKey", command.familyKey())
                .addValue("title", command.title())
                .addValue("subtitle", command.subtitle())
                .addValue("description", command.description())
                .addValue("ctaLabel", command.ctaLabel())
                .addValue("ctaHref", command.ctaHref())
                .addValue("sortOrder", command.sortOrder())
                .addValue("featured", command.featured())
                .addValue("metadataJson", json(command.metadata()));

        int updatedItems = jdbcTemplate.update("""
                UPDATE storefront_home_items
                SET family_key = COALESCE(:familyKey, family_key),
                    title = COALESCE(:title, title),
                    subtitle = COALESCE(:subtitle, subtitle),
                    description = COALESCE(:description, description),
                    cta_label = COALESCE(:ctaLabel, cta_label),
                    cta_href = COALESCE(:ctaHref, cta_href),
                    sort_order = COALESCE(:sortOrder, sort_order),
                    is_featured = COALESCE(:featured, is_featured),
                    metadata = metadata || COALESCE(CAST(:metadataJson AS jsonb), '{}'::jsonb),
                    updated_at = now()
                WHERE item_key = :itemKey AND is_active = TRUE
                """, parameters);

        if (updatedItems == 0) {
            throw new StorefrontHomeItemNotFoundException(command.itemKey());
        }

                if (command.mediaAssetKey() != null) {
                        int linked = jdbcTemplate.update("""
                                        UPDATE storefront_home_items item
                                        SET media_asset_id = media.id, updated_at = now()
                                        FROM media_assets media
                                        WHERE item.item_key = :itemKey
                                            AND item.is_active = TRUE
                                            AND media.asset_key = :mediaAssetKey
                                            AND media.product_sku = :itemKey
                                            AND media.media_type = 'PRODUCT_IMAGE'
                                            AND media.status = 'READY'
                                            AND media.is_active = TRUE
                                        """, new MapSqlParameterSource()
                                        .addValue("itemKey", command.itemKey())
                                        .addValue("mediaAssetKey", command.mediaAssetKey()));
                        if (linked == 0) {
                            throw new StorefrontMediaAssignmentException();
                        }
                }

        if (command.demoVideoAssetKey() != null) {
            updateVideoAsset(command.itemKey(), command.demoVideoAssetKey());
        }

        if (command.galleryAssetKeys() != null) {
            updateGallerySlots(command.itemKey(), command.galleryAssetKeys());
        }
    }

    @Override
    public String findItemImageAssetKeyForUpdate(String itemKey) {
        return currentItemAssetKey(itemKey, "media_asset_id");
    }

    @Override
    public String findItemVideoAssetKeyForUpdate(String itemKey) {
        return currentItemAssetKey(itemKey, "video_media_asset_id");
    }

    @Override
    public String findGalleryAssetKeyForUpdate(String itemKey, int slot) {
        List<String> keys = jdbcTemplate.queryForList("""
                SELECT media.asset_key
                FROM storefront_home_item_gallery gallery
                JOIN storefront_home_items item ON item.id = gallery.item_id
                JOIN media_assets media ON media.id = gallery.media_asset_id
                WHERE item.item_key = :itemKey
                  AND gallery.sort_order = :slot
                  AND gallery.is_active = TRUE
                FOR UPDATE OF gallery
                """, new MapSqlParameterSource()
                .addValue("itemKey", itemKey)
                .addValue("slot", slot), String.class);
        return keys.isEmpty() ? null : keys.getFirst();
    }

    private String currentItemAssetKey(String itemKey, String mediaColumn) {
        List<String> keys = jdbcTemplate.queryForList("""
                SELECT media.asset_key
                FROM storefront_home_items item
                LEFT JOIN media_assets media ON media.id = item.%s
                WHERE item.item_key = :itemKey AND item.is_active = TRUE
                FOR UPDATE OF item
                """.formatted(mediaColumn), new MapSqlParameterSource("itemKey", itemKey), String.class);
        return keys.isEmpty() ? null : keys.getFirst();
    }

    @Override
    public void updateDisplayMedia(String itemKey, String imageAssetKey, String videoAssetKey) {
                if ((imageAssetKey == null) == (videoAssetKey == null)) {
                        throw new IllegalArgumentException("Exactly one display image or video asset is required");
                }
        if (imageAssetKey != null) {
            int linked = jdbcTemplate.update("""
                    UPDATE storefront_home_items item
                    SET media_asset_id = media.id, updated_at = now()
                                        FROM media_assets media, storefront_home_sections section
                    WHERE item.item_key = :itemKey
                      AND item.is_active = TRUE
                                            AND section.id = item.section_id
                                            AND section.section_key <> 'bestsellers'
                      AND media.asset_key = :assetKey
                      AND (media.media_type IN ('PRODUCT_IMAGE', 'DISPLAY_IMAGE') OR media.content_type LIKE 'image/%')
                      AND media.status = 'READY'
                      AND media.is_active = TRUE
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", itemKey)
                    .addValue("assetKey", imageAssetKey));
            if (linked == 0) {
                throw new StorefrontMediaAssignmentException("A READY image asset is required");
            }
        }
        if (videoAssetKey != null) {
            int linked = jdbcTemplate.update("""
                    UPDATE storefront_home_items item
                    SET video_media_asset_id = media.id, demo_video_url = NULL, updated_at = now()
                                        FROM media_assets media, storefront_home_sections section
                    WHERE item.item_key = :itemKey
                      AND item.is_active = TRUE
                                            AND section.id = item.section_id
                                            AND section.section_key = 'brand'
                      AND media.asset_key = :assetKey
                      AND media.media_type = 'DISPLAY_VIDEO'
                      AND media.status = 'READY'
                      AND media.is_active = TRUE
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", itemKey)
                    .addValue("assetKey", videoAssetKey));
            if (linked == 0) {
                throw new StorefrontMediaAssignmentException("A READY display video is required");
            }
        }
    }

    @Override
    public void updateGallerySlot(String itemKey, int slot, String assetKey) {
        if (assetKey != null && !assetKey.isBlank()) {
            int linked = jdbcTemplate.update("""
                    INSERT INTO storefront_home_item_gallery (item_id, media_asset_id, sort_order)
                    SELECT item.id, media.id, :sortOrder
                    FROM storefront_home_items item
                    JOIN media_assets media ON media.asset_key = :assetKey
                        AND media.product_sku = item.item_key
                        AND media.media_type = 'PRODUCT_IMAGE'
                        AND media.status = 'READY'
                        AND media.is_active = TRUE
                    WHERE item.item_key = :itemKey AND item.is_active = TRUE
                    ON CONFLICT (item_id, sort_order) DO UPDATE
                        SET media_asset_id = EXCLUDED.media_asset_id,
                            is_active = TRUE,
                            updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", itemKey)
                    .addValue("assetKey", assetKey.trim())
                    .addValue("sortOrder", slot));
            if (linked == 0) {
                throw new StorefrontMediaAssignmentException();
            }
        } else {
            jdbcTemplate.update("""
                    UPDATE storefront_home_item_gallery g
                    SET is_active = FALSE, updated_at = now()
                    FROM storefront_home_items item
                    WHERE g.item_id = item.id
                      AND item.item_key = :itemKey
                      AND g.sort_order = :sortOrder
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", itemKey)
                    .addValue("sortOrder", slot));
        }
    }

    @Override
    public void createItem(StorefrontHomeItemCreateCommand command) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sectionKey", command.sectionKey())
                .addValue("itemKey", command.itemKey())
                .addValue("familyKey", command.familyKey())
                .addValue("title", command.title())
                .addValue("subtitle", command.subtitle())
                .addValue("description", command.description())
                .addValue("ctaLabel", command.ctaLabel())
                .addValue("ctaHref", command.ctaHref())
                .addValue("sortOrder", command.sortOrder())
                .addValue("featured", command.featured())
                .addValue("metadataJson", json(command.metadata() != null ? command.metadata() : Map.of()))
                .addValue("mediaAssetKey", command.mediaAssetKey());

        int inserted = jdbcTemplate.update("""
                INSERT INTO storefront_home_items
                    (section_id, item_key, family_key, title, subtitle, description,
                     cta_label, cta_href, sort_order, is_featured, metadata, media_asset_id)
                SELECT s.id,
                       :itemKey, :familyKey, :title, :subtitle, :description,
                       :ctaLabel, :ctaHref, :sortOrder, :featured,
                       CAST(:metadataJson AS jsonb),
                       m.id
                FROM storefront_home_sections s
                JOIN media_assets m ON m.asset_key = :mediaAssetKey
                    AND m.product_sku = :itemKey
                    AND m.media_type = 'PRODUCT_IMAGE'
                    AND m.status = 'READY'
                    AND m.is_active = TRUE
                WHERE s.section_key = :sectionKey AND s.is_active = TRUE
                """, parameters);
        if (inserted == 0) {
            throw new StorefrontMediaAssignmentException("A READY primary product image owned by this product is required");
        }

        if (command.galleryAssetKeys() != null && !command.galleryAssetKeys().isEmpty()) {
            updateGallerySlots(command.itemKey(), command.galleryAssetKeys());
        }
        if (command.demoVideoAssetKey() != null) {
            updateVideoAsset(command.itemKey(), command.demoVideoAssetKey());
        }
    }

    @Override
    public void assertUniqueProductIdentity(String itemKey, String sku, String slug) {
        jdbcTemplate.getJdbcTemplate().query(
            "SELECT pg_advisory_xact_lock(hashtext('storefront-product-identity'))",
            resultSet -> null
        );
        Integer conflicts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                WHERE section.section_key = 'bestsellers'
                  AND item.is_active = TRUE
                  AND item.item_key <> :itemKey
                  AND (item.metadata ->> 'sku' = :sku OR item.metadata ->> 'slug' = :slug)
                """, new MapSqlParameterSource()
                .addValue("itemKey", itemKey)
                .addValue("sku", sku)
                .addValue("slug", slug), Integer.class);
        if (conflicts != null && conflicts > 0) {
            throw new StorefrontProductIdentityConflictException();
        }
    }

    private void updateVideoAsset(String itemKey, String assetKey) {
        if (assetKey.isBlank()) {
            jdbcTemplate.update("""
                    UPDATE storefront_home_items
                    SET video_media_asset_id = NULL, updated_at = now()
                    WHERE item_key = :itemKey AND is_active = TRUE
                    """, new MapSqlParameterSource("itemKey", itemKey));
            return;
        }
        int linked = jdbcTemplate.update("""
                UPDATE storefront_home_items item
                SET video_media_asset_id = media.id, updated_at = now()
                FROM media_assets media
                WHERE item.item_key = :itemKey
                  AND item.is_active = TRUE
                  AND media.asset_key = :assetKey
                  AND media.product_sku = item.item_key
                  AND media.media_type = 'PRODUCT_VIDEO'
                  AND media.status = 'READY'
                  AND media.is_active = TRUE
                """, new MapSqlParameterSource()
                .addValue("itemKey", itemKey)
                .addValue("assetKey", assetKey.trim()));
        if (linked == 0) {
            throw new StorefrontMediaAssignmentException("A READY product video owned by this product is required");
        }
    }

    private void updateGallerySlots(String itemKey, List<String> galleryAssetKeys) {
        for (int i = 0; i < Math.min(galleryAssetKeys.size(), 4); i++) {
            int sortOrder = i + 1;
            String assetKey = galleryAssetKeys.get(i);
            if (assetKey != null && !assetKey.isBlank()) {
                int linked = jdbcTemplate.update("""
                        INSERT INTO storefront_home_item_gallery (item_id, media_asset_id, sort_order)
                        SELECT item.id, media.id, :sortOrder
                        FROM storefront_home_items item
                        JOIN media_assets media ON media.asset_key = :assetKey
                            AND media.product_sku = item.item_key
                            AND media.media_type = 'PRODUCT_IMAGE'
                            AND media.status = 'READY'
                            AND media.is_active = TRUE
                        WHERE item.item_key = :itemKey AND item.is_active = TRUE
                        ON CONFLICT (item_id, sort_order) DO UPDATE
                            SET media_asset_id = EXCLUDED.media_asset_id,
                                is_active = TRUE,
                                updated_at = now()
                        """, new MapSqlParameterSource()
                        .addValue("itemKey", itemKey)
                        .addValue("assetKey", assetKey.trim())
                        .addValue("sortOrder", sortOrder));
                if (linked == 0) {
                    throw new StorefrontMediaAssignmentException();
                }
            } else {
                jdbcTemplate.update("""
                        UPDATE storefront_home_item_gallery g
                        SET is_active = FALSE, updated_at = now()
                        FROM storefront_home_items item
                        WHERE g.item_id = item.id
                          AND item.item_key = :itemKey
                          AND g.sort_order = :sortOrder
                        """, new MapSqlParameterSource()
                        .addValue("itemKey", itemKey)
                        .addValue("sortOrder", sortOrder));
            }
        }
    }

    private MediaRow media(ResultSet rs) throws SQLException {
        String assetKey = rs.getString("asset_key");
        if (assetKey == null) {
            return null;
        }

        return new MediaRow(
                assetKey,
                rs.getString("asset_url"),
                rs.getString("alt_text"),
                rs.getInt("width_px"),
                rs.getInt("height_px"),
                rs.getString("delivery_mode"),
                rs.getInt("version"),
                jsonStringList(rs, "tags")
        );
    }

    private String videoUrl(String storageKey, String websiteVideoUrl) {
        return StringUtils.hasText(storageKey) ? mediaUrlBuilder.assetUrl(storageKey) : websiteVideoUrl;
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private Map<String, Object> jsonObject(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(json, STRING_OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid JSON object in column " + column, exception);
        }
    }

    private List<String> jsonStringList(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid JSON list in column " + column, exception);
        }
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid storefront metadata", exception);
        }
    }
}

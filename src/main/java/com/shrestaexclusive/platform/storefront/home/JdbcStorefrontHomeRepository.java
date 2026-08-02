package com.shrestaexclusive.platform.storefront.home;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.GalleryRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.ItemRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.MediaRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.home.StorefrontHomeRepository.VariantRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStorefrontHomeRepository implements StorefrontHomeRepository {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcStorefrontHomeRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        if (sectionKeys.isEmpty()) {
            return List.of();
        }

        List<ItemRow> items = jdbcTemplate.query("""
                SELECT item.id, section.section_key, item.item_key, item.family_key, item.title,
                       item.subtitle, item.description, item.cta_label, item.cta_href,
                       item.sort_order, item.is_featured, item.metadata, item.demo_video_url,
                       media.asset_key, media.asset_url, media.alt_text, media.width_px,
                       media.height_px, media.delivery_mode, media.version, media.lqip_data_url
                FROM storefront_home_items item
                JOIN storefront_home_sections section ON section.id = item.section_id
                LEFT JOIN media_assets media ON media.id = item.media_asset_id AND media.is_active = TRUE
                WHERE item.is_active = TRUE AND section.section_key IN (:sectionKeys)
                ORDER BY section.sort_order, item.sort_order, item.title
                """, new MapSqlParameterSource("sectionKeys", sectionKeys), (rs, rowNum) -> new ItemRow(
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
                rs.getString("demo_video_url")
        ));

        Map<String, List<VariantRow>> variantsByAsset = findVariantsByAssetKey(items.stream()
                .map(ItemRow::media)
                .filter(row -> row != null)
                .map(MediaRow::assetKey)
                .distinct()
                .toList());

        return items.stream()
                .map(item -> withVariants(item, variantsByAsset))
                .toList();
    }

    @Override
    public Map<UUID, List<GalleryRow>> findGalleryByItemIds(List<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        List<GalleryRow> rows = jdbcTemplate.query("""
                SELECT gallery.item_id, gallery.sort_order,
                       media.asset_key, media.asset_url, media.alt_text, media.width_px,
                       media.height_px, media.delivery_mode, media.version, media.lqip_data_url
                FROM storefront_home_item_gallery gallery
                JOIN media_assets media ON media.id = gallery.media_asset_id AND media.is_active = TRUE
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
                        rs.getString("lqip_data_url"),
                        List.of()
                )
        ));

        List<String> galleryAssetKeys = rows.stream()
                .map(row -> row.media().assetKey())
                .distinct()
                .toList();
        Map<String, List<VariantRow>> variantsByAsset = findVariantsByAssetKey(galleryAssetKeys);

        Map<UUID, List<GalleryRow>> galleryByItemId = new LinkedHashMap<>();
        for (GalleryRow row : rows) {
            MediaRow mediaWithVariants = new MediaRow(
                    row.media().assetKey(),
                    row.media().assetUrl(),
                    row.media().altText(),
                    row.media().widthPx(),
                    row.media().heightPx(),
                    row.media().deliveryMode(),
                    row.media().version(),
                    row.media().lqipDataUrl(),
                    variantsByAsset.getOrDefault(row.media().assetKey(), List.of())
            );
            galleryByItemId
                    .computeIfAbsent(row.itemId(), id -> new ArrayList<>())
                    .add(new GalleryRow(row.itemId(), row.sortOrder(), mediaWithVariants));
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
                .addValue("metadataJson", json(command.metadata()))
                .addValue("mediaUrl", command.mediaUrl(), Types.VARCHAR)
                .addValue("mediaAltText", command.mediaAltText(), Types.VARCHAR)
                .addValue("mediaWidthPx", command.mediaWidthPx(), Types.INTEGER)
                .addValue("mediaHeightPx", command.mediaHeightPx(), Types.INTEGER)
                .addValue("mediaDeliveryMode", command.mediaDeliveryMode(), Types.VARCHAR);

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
                    metadata = COALESCE(CAST(:metadataJson AS jsonb), metadata),
                    updated_at = now()
                WHERE item_key = :itemKey AND is_active = TRUE
                """, parameters);

        if (updatedItems == 0) {
            throw new StorefrontHomeItemNotFoundException(command.itemKey());
        }

        jdbcTemplate.update("""
                UPDATE media_assets media
                SET asset_url = COALESCE(:mediaUrl, media.asset_url),
                    alt_text = COALESCE(:mediaAltText, media.alt_text),
                    width_px = COALESCE(:mediaWidthPx, media.width_px),
                    height_px = COALESCE(:mediaHeightPx, media.height_px),
                    delivery_mode = COALESCE(:mediaDeliveryMode, media.delivery_mode),
                    version = version + CASE WHEN :mediaUrl IS NULL THEN 0 ELSE 1 END,
                    updated_at = now()
                FROM storefront_home_items item
                WHERE item.media_asset_id = media.id
                  AND item.item_key = :itemKey
                  AND media.is_active = TRUE
                """, parameters);

        if (command.demoVideoUrl() != null) {
            String videoUrl = command.demoVideoUrl().isBlank() ? null : command.demoVideoUrl();
            jdbcTemplate.update("""
                    UPDATE storefront_home_items
                    SET demo_video_url = :demoVideoUrl, updated_at = now()
                    WHERE item_key = :itemKey AND is_active = TRUE
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", command.itemKey())
                    .addValue("demoVideoUrl", videoUrl));
        }

        if (command.galleryAssetKeys() != null) {
            updateGallerySlots(command.itemKey(), command.galleryAssetKeys());
        }
    }

    @Override
    public void updateGallerySlot(String itemKey, int slot, String assetKey) {
        if (assetKey != null && !assetKey.isBlank()) {
            jdbcTemplate.update("""
                    INSERT INTO storefront_home_item_gallery (item_id, media_asset_id, sort_order)
                    SELECT item.id, media.id, :sortOrder
                    FROM storefront_home_items item
                    JOIN media_assets media ON media.asset_key = :assetKey AND media.is_active = TRUE
                    WHERE item.item_key = :itemKey AND item.is_active = TRUE
                    ON CONFLICT (item_id, sort_order) DO UPDATE
                        SET media_asset_id = EXCLUDED.media_asset_id,
                            is_active = TRUE,
                            updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("itemKey", itemKey)
                    .addValue("assetKey", assetKey.trim())
                    .addValue("sortOrder", slot));
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
                .addValue("mediaAssetKey", command.mediaAssetKey())
                .addValue("demoVideoUrl", command.demoVideoUrl());

        jdbcTemplate.update("""
                INSERT INTO storefront_home_items
                    (section_id, item_key, family_key, title, subtitle, description,
                     cta_label, cta_href, sort_order, is_featured, metadata, media_asset_id, demo_video_url)
                SELECT s.id,
                       :itemKey, :familyKey, :title, :subtitle, :description,
                       :ctaLabel, :ctaHref, :sortOrder, :featured,
                       CAST(:metadataJson AS jsonb),
                       m.id, :demoVideoUrl
                FROM storefront_home_sections s
                LEFT JOIN media_assets m ON m.asset_key = :mediaAssetKey AND m.is_active = TRUE
                WHERE s.section_key = :sectionKey AND s.is_active = TRUE
                """, parameters);

        if (command.galleryAssetKeys() != null && !command.galleryAssetKeys().isEmpty()) {
            updateGallerySlots(command.itemKey(), command.galleryAssetKeys());
        }
    }

    private void updateGallerySlots(String itemKey, List<String> galleryAssetKeys) {
        for (int i = 0; i < Math.min(galleryAssetKeys.size(), 4); i++) {
            int sortOrder = i + 1;
            String assetKey = galleryAssetKeys.get(i);
            if (assetKey != null && !assetKey.isBlank()) {
                jdbcTemplate.update("""
                        INSERT INTO storefront_home_item_gallery (item_id, media_asset_id, sort_order)
                        SELECT item.id, media.id, :sortOrder
                        FROM storefront_home_items item
                        JOIN media_assets media ON media.asset_key = :assetKey AND media.is_active = TRUE
                        WHERE item.item_key = :itemKey AND item.is_active = TRUE
                        ON CONFLICT (item_id, sort_order) DO UPDATE
                            SET media_asset_id = EXCLUDED.media_asset_id,
                                is_active = TRUE,
                                updated_at = now()
                        """, new MapSqlParameterSource()
                        .addValue("itemKey", itemKey)
                        .addValue("assetKey", assetKey.trim())
                        .addValue("sortOrder", sortOrder));
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
                rs.getString("lqip_data_url"),
                List.of()
        );
    }

    private ItemRow withVariants(ItemRow item, Map<String, List<VariantRow>> variantsByAsset) {
        MediaRow media = item.media();
        if (media == null) {
            return item;
        }

        MediaRow mediaWithVariants = new MediaRow(
                media.assetKey(),
                media.assetUrl(),
                media.altText(),
                media.widthPx(),
                media.heightPx(),
                media.deliveryMode(),
                media.version(),
                media.lqipDataUrl(),
                variantsByAsset.getOrDefault(media.assetKey(), List.of())
        );
        return new ItemRow(
                item.id(),
                item.sectionKey(),
                item.itemKey(),
                item.familyKey(),
                item.title(),
                item.subtitle(),
                item.description(),
                item.ctaLabel(),
                item.ctaHref(),
                item.sortOrder(),
                item.featured(),
                item.metadata(),
                mediaWithVariants,
                item.demoVideoUrl()
        );
    }

    private Map<String, List<VariantRow>> findVariantsByAssetKey(List<String> assetKeys) {
        if (assetKeys.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.query("""
                SELECT asset.asset_key, variant.variant_key, variant.format, variant.width_px,
                       variant.height_px, variant.byte_size, variant.url_path
                FROM media_asset_variants variant
                JOIN media_assets asset ON asset.id = variant.asset_id
                WHERE asset.asset_key IN (:assetKeys) AND variant.is_active = TRUE
                ORDER BY asset.asset_key, variant.width_px, variant.format
                """, new MapSqlParameterSource("assetKeys", assetKeys), (rs, rowNum) -> Map.entry(
                rs.getString("asset_key"),
                new VariantRow(
                        rs.getString("variant_key"),
                        rs.getString("format"),
                        rs.getInt("width_px"),
                        rs.getInt("height_px"),
                        rs.getLong("byte_size"),
                        rs.getString("url_path")
                )
        )).stream().collect(Collectors.groupingBy(
                Map.Entry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
        ));
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

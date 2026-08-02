package com.shrestaexclusive.platform.db.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the database from JSON files in classpath:db/seed/.
 *
 * Runs only in local / dev / uat profiles. Safe to run on every boot:
 * every statement uses ON CONFLICT DO UPDATE so re-seeding is idempotent.
 *
 * Seed order (respects FK dependencies):
 *   category → media → storefront → store → products → auth
 *
 * Each JSON file contains a flat array of row objects whose field names
 * match the DB column names. Foreign-key IDs are resolved by natural key
 * look-up inside each seed method.
 *
 * Image paths in JSON (image_path field) reference files under
 * classpath:db/seed/shresta-media/ and are stored for future MinIO upload.
 */
@Component
@Profile({"local", "dev", "uat"})
@Order(Integer.MAX_VALUE)           // runs after all Flyway migrations
public class DatabaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseSeeder(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        log.info("[seed] Starting database seeding for non-prod environment");
        seedCategoryFamilies();
        seedCategoryProductTypes();
        seedCategoryAttributes();
        seedCategoryFilters();
        seedCategoryTax();
        seedCategoryStyling();
        seedMediaReferenceAssets();
        seedMediaProductAssets();
        seedMediaVariants();
        seedStorefrontHomeSections();
        seedStorefrontHomeItems();
        seedStorefrontLocatorSections();
        seedStoreLocations();
        seedProducts();
        seedDevAuthAccounts();
        log.info("[seed] Database seeding complete");
    }

    // =========================================================================
    // Category
    // =========================================================================

    private void seedCategoryFamilies() throws Exception {
        String sql = """
            INSERT INTO category_family_config
                (family_key, display_name, description, is_active, sort_order, metadata)
            VALUES
                (:family_key, :display_name, :description, :is_active, :sort_order, :metadata::jsonb)
            ON CONFLICT (family_key) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                description  = EXCLUDED.description,
                is_active    = EXCLUDED.is_active,
                sort_order   = EXCLUDED.sort_order,
                metadata     = EXCLUDED.metadata,
                updated_at   = now()
            """;
        int n = upsertAll("category/families.json", sql, row -> params(row)
            .addValue("metadata", jsonb(row, "metadata")));
        log.info("[seed] category_family_config: {} upserted", n);
    }

    private void seedCategoryProductTypes() throws Exception {
        String sql = """
            INSERT INTO category_product_type_config
                (family_id, type_key, display_name, is_active, sort_order, metadata)
            SELECT f.id, :type_key, :display_name, :is_active, :sort_order, :metadata::jsonb
              FROM category_family_config f WHERE f.family_key = :family_key
            ON CONFLICT (family_id, type_key) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                is_active    = EXCLUDED.is_active,
                sort_order   = EXCLUDED.sort_order,
                metadata     = EXCLUDED.metadata,
                updated_at   = now()
            """;
        int n = upsertAll("category/product-types.json", sql, row -> params(row)
            .addValue("metadata", jsonb(row, "metadata")));
        log.info("[seed] category_product_type_config: {} upserted", n);
    }

    private void seedCategoryAttributes() throws Exception {
        String sql = """
            INSERT INTO category_attribute_config
                (family_id, attribute_key, display_name, data_type, is_required,
                 is_filterable, is_searchable, is_active, allowed_values, sort_order)
            SELECT f.id, :attribute_key, :display_name, :data_type, :is_required,
                   :is_filterable, :is_searchable, :is_active, :allowed_values::jsonb, :sort_order
              FROM category_family_config f WHERE f.family_key = :family_key
            ON CONFLICT (family_id, attribute_key) DO UPDATE SET
                display_name   = EXCLUDED.display_name,
                data_type      = EXCLUDED.data_type,
                is_filterable  = EXCLUDED.is_filterable,
                is_searchable  = EXCLUDED.is_searchable,
                is_active      = EXCLUDED.is_active,
                allowed_values = EXCLUDED.allowed_values,
                sort_order     = EXCLUDED.sort_order,
                updated_at     = now()
            """;
        int n = upsertAll("category/attributes.json", sql, row -> params(row)
            .addValue("allowed_values", jsonb(row, "allowed_values")));
        log.info("[seed] category_attribute_config: {} upserted", n);
    }

    private void seedCategoryFilters() throws Exception {
        String sql = """
            INSERT INTO category_filter_config
                (family_id, attribute_id, filter_key, display_name,
                 frontend_control, backend_mapping, is_active, sort_order)
            SELECT f.id, a.id, :filter_key, :display_name,
                   :frontend_control, :backend_mapping, :is_active, :sort_order
              FROM category_family_config f
              JOIN category_attribute_config a ON a.family_id = f.id
             WHERE f.family_key = :family_key
               AND a.attribute_key = :filter_key
            ON CONFLICT (family_id, filter_key) DO UPDATE SET
                display_name     = EXCLUDED.display_name,
                frontend_control = EXCLUDED.frontend_control,
                backend_mapping  = EXCLUDED.backend_mapping,
                is_active        = EXCLUDED.is_active,
                sort_order       = EXCLUDED.sort_order,
                updated_at       = now()
            """;
        int n = upsertAll("category/filters.json", sql, row -> params(row));
        log.info("[seed] category_filter_config: {} upserted", n);
    }

    private void seedCategoryTax() throws Exception {
        String sql = """
            INSERT INTO category_tax_config
                (family_id, hsn_code, gst_rate_basis_points, effective_from, effective_to, is_active)
            SELECT f.id, :hsn_code, :gst_rate_basis_points, :effective_from::date, :effective_to::date, :is_active
              FROM category_family_config f WHERE f.family_key = :family_key
            ON CONFLICT DO NOTHING
            """;
        int n = upsertAll("category/tax.json", sql, row -> params(row));
        log.info("[seed] category_tax_config: {} upserted", n);
    }

    private void seedCategoryStyling() throws Exception {
        String sql = """
            INSERT INTO category_styling_config
                (family_id, occasion_key, display_name, complementary_family_keys,
                 rules, is_active, sort_order)
            SELECT f.id, :occasion_key, :display_name, :complementary_family_keys::jsonb,
                   :rules::jsonb, :is_active, :sort_order
              FROM category_family_config f WHERE f.family_key = :family_key
            ON CONFLICT (family_id, occasion_key) DO UPDATE SET
                display_name              = EXCLUDED.display_name,
                complementary_family_keys = EXCLUDED.complementary_family_keys,
                rules                     = EXCLUDED.rules,
                is_active                 = EXCLUDED.is_active,
                sort_order                = EXCLUDED.sort_order,
                updated_at                = now()
            """;
        int n = upsertAll("category/styling.json", sql, row -> params(row)
            .addValue("complementary_family_keys", jsonb(row, "complementary_family_keys"))
            .addValue("rules", jsonb(row, "rules")));
        log.info("[seed] category_styling_config: {} upserted", n);
    }

    // =========================================================================
    // Media
    // =========================================================================

    private void seedMediaReferenceAssets() throws Exception {
        seedMediaAssets("media/reference-assets.json");
        log.info("[seed] media_assets (reference): upserted");
    }

    private void seedMediaProductAssets() throws Exception {
        seedMediaAssets("media/product-assets.json");
        log.info("[seed] media_assets (products): upserted");
    }

    private void seedMediaAssets(String jsonPath) throws Exception {
        String sql = """
            INSERT INTO media_assets
                (asset_key, asset_url, alt_text, width_px, height_px, delivery_mode,
                 usage_type, storage_provider, storage_key, category_family_key,
                 category_product_type_key, product_sku, content_type, status, tags,
                 seo_title, metadata)
            VALUES
                (:asset_key,
                 COALESCE(:storage_key, :asset_key),
                 :alt_text, :width_px, :height_px,
                 COALESCE(:delivery_mode, 's3-compatible'),
                 :usage_type,
                 COALESCE(:storage_provider, 's3-compatible'),
                 :storage_key,
                 :category_family_key, :category_product_type_key, :product_sku,
                 :content_type,
                 COALESCE(:status, 'READY'),
                 :tags::jsonb, :seo_title, '{}'::jsonb)
            ON CONFLICT (asset_key) DO UPDATE SET
                alt_text                  = EXCLUDED.alt_text,
                width_px                  = EXCLUDED.width_px,
                height_px                 = EXCLUDED.height_px,
                storage_key               = EXCLUDED.storage_key,
                category_family_key       = EXCLUDED.category_family_key,
                category_product_type_key = EXCLUDED.category_product_type_key,
                product_sku               = EXCLUDED.product_sku,
                content_type              = EXCLUDED.content_type,
                tags                      = EXCLUDED.tags,
                seo_title                 = EXCLUDED.seo_title,
                updated_at                = now()
            """;
        upsertAll(jsonPath, sql, row -> params(row)
            .addValue("tags", jsonb(row, "tags")));
    }

    private void seedMediaVariants() throws Exception {
        String sql = """
            INSERT INTO media_asset_variants
                (asset_id, variant_key, format, width_px, height_px,
                 byte_size, storage_key, url_path, content_type, is_active)
            SELECT ma.id, :variant_key, :format, :width_px, :height_px,
                   COALESCE(:byte_size, 0), :storage_key, :url_path, :content_type,
                   COALESCE(:is_active, true)
              FROM media_assets ma WHERE ma.asset_key = :asset_key
            ON CONFLICT (asset_id, variant_key, format) DO UPDATE SET
                width_px   = EXCLUDED.width_px,
                height_px  = EXCLUDED.height_px,
                storage_key = EXCLUDED.storage_key,
                url_path   = EXCLUDED.url_path,
                updated_at = now()
            """;
        int n = upsertAll("media/variants.json", sql, row -> params(row));
        log.info("[seed] media_asset_variants: {} upserted", n);
    }

    // =========================================================================
    // Storefront
    // =========================================================================

    private void seedStorefrontHomeSections() throws Exception {
        String sql = """
            INSERT INTO storefront_home_sections
                (section_key, section_type, eyebrow, title, description,
                 sort_order, metadata, is_active)
            VALUES
                (:section_key, :section_type, :eyebrow, :title, :description,
                 :sort_order, :metadata::jsonb, :is_active)
            ON CONFLICT (section_key) DO UPDATE SET
                section_type = EXCLUDED.section_type,
                eyebrow      = EXCLUDED.eyebrow,
                title        = EXCLUDED.title,
                description  = EXCLUDED.description,
                sort_order   = EXCLUDED.sort_order,
                metadata     = EXCLUDED.metadata,
                is_active    = EXCLUDED.is_active,
                updated_at   = now()
            """;
        int n = upsertAll("storefront/home-sections.json", sql, row -> params(row)
            .addValue("metadata", jsonb(row, "metadata")));
        log.info("[seed] storefront_home_sections: {} upserted", n);
    }

    private void seedStorefrontHomeItems() throws Exception {
        String sql = """
            INSERT INTO storefront_home_items
                (section_id, item_key, family_key, title, subtitle, description,
                 cta_label, cta_href, sort_order, is_featured, media_asset_id,
                 demo_video_url, metadata, is_active)
            SELECT hs.id, :item_key, :family_key, :title, :subtitle, :description,
                   :cta_label, :cta_href, :sort_order, :is_featured,
                   ma.id, :demo_video_url, :metadata::jsonb, :is_active
              FROM storefront_home_sections hs
              LEFT JOIN media_assets ma ON ma.asset_key = :media_asset_key
             WHERE hs.section_key = :section_key
            ON CONFLICT (item_key) DO UPDATE SET
                family_key     = EXCLUDED.family_key,
                title          = EXCLUDED.title,
                subtitle       = EXCLUDED.subtitle,
                description    = EXCLUDED.description,
                cta_label      = EXCLUDED.cta_label,
                cta_href       = EXCLUDED.cta_href,
                sort_order     = EXCLUDED.sort_order,
                is_featured    = EXCLUDED.is_featured,
                media_asset_id = EXCLUDED.media_asset_id,
                demo_video_url = EXCLUDED.demo_video_url,
                metadata       = EXCLUDED.metadata,
                is_active      = EXCLUDED.is_active,
                updated_at     = now()
            """;
        int n = upsertAll("storefront/home-items.json", sql, row -> params(row)
            .addValue("metadata",       jsonb(row, "metadata"))
            .addValue("subtitle",       row.getOrDefault("subtitle",       null))
            .addValue("cta_label",      row.getOrDefault("cta_label",      null))
            .addValue("cta_href",       row.getOrDefault("cta_href",       null))
            .addValue("demo_video_url", row.getOrDefault("demo_video_url", null))
            .addValue("media_asset_key",row.getOrDefault("media_asset_key",null)));
        log.info("[seed] storefront_home_items (non-product): {} upserted", n);
    }

    // =========================================================================
    // Store locator
    // =========================================================================

    private void seedStorefrontLocatorSections() throws Exception {
        String sql = """
            INSERT INTO storefront_store_sections
                (section_key, eyebrow, title, description, metadata, is_active, sort_order)
            VALUES
                (:section_key, :eyebrow, :title, :description, :metadata::jsonb, :is_active, :sort_order)
            ON CONFLICT (section_key) DO UPDATE SET
                eyebrow    = EXCLUDED.eyebrow,
                title      = EXCLUDED.title,
                description = EXCLUDED.description,
                metadata   = EXCLUDED.metadata,
                is_active  = EXCLUDED.is_active,
                sort_order = EXCLUDED.sort_order,
                updated_at = now()
            """;
        int n = upsertAll("store/locator-sections.json", sql, row -> params(row)
            .addValue("metadata", jsonb(row, "metadata")));
        log.info("[seed] storefront_store_sections: {} upserted", n);
    }

    private void seedStoreLocations() throws Exception {
        String sql = """
            INSERT INTO store_locations
                (store_key, display_name, short_name, status,
                 address_line1, address_line2, locality, city, state,
                 postal_code, country_code, phone, whatsapp_number, email,
                 latitude, longitude, supported_family_keys, service_modes,
                 highlights, opening_hours, fulfillment, is_active, sort_order)
            VALUES
                (:store_key, :display_name, :short_name, :status,
                 :address_line1, :address_line2, :locality, :city, :state,
                 :postal_code, COALESCE(:country_code,'IN'), :phone, :whatsapp_number, :email,
                 :latitude, :longitude,
                 :supported_family_keys::jsonb, :service_modes::jsonb,
                 :highlights::jsonb, :opening_hours::jsonb, :fulfillment::jsonb,
                 :is_active, :sort_order)
            ON CONFLICT (store_key) DO UPDATE SET
                display_name          = EXCLUDED.display_name,
                short_name            = EXCLUDED.short_name,
                status                = EXCLUDED.status,
                address_line1         = EXCLUDED.address_line1,
                address_line2         = EXCLUDED.address_line2,
                locality              = EXCLUDED.locality,
                phone                 = EXCLUDED.phone,
                whatsapp_number       = EXCLUDED.whatsapp_number,
                supported_family_keys = EXCLUDED.supported_family_keys,
                service_modes         = EXCLUDED.service_modes,
                highlights            = EXCLUDED.highlights,
                opening_hours         = EXCLUDED.opening_hours,
                fulfillment           = EXCLUDED.fulfillment,
                is_active             = EXCLUDED.is_active,
                sort_order            = EXCLUDED.sort_order,
                updated_at            = now()
            """;
        int n = upsertAll("store/locations.json", sql, row -> params(row)
            .addValue("supported_family_keys", jsonb(row, "supported_family_keys"))
            .addValue("service_modes",         jsonb(row, "service_modes"))
            .addValue("highlights",            jsonb(row, "highlights"))
            .addValue("opening_hours",         jsonb(row, "opening_hours"))
            .addValue("fulfillment",           jsonb(row, "fulfillment")));
        log.info("[seed] store_locations: {} upserted", n);
    }

    // =========================================================================
    // Products (storefront_home_items in the bestsellers section)
    // =========================================================================

    private void seedProducts() throws Exception {
        String sql = """
            INSERT INTO storefront_home_items
                (section_id, item_key, family_key, title, subtitle, description,
                 cta_label, cta_href, sort_order, is_featured, media_asset_id,
                 demo_video_url, metadata, is_active)
            SELECT hs.id, :item_key, :family_key, :title, :subtitle, :description,
                   :cta_label, :cta_href, :sort_order, :is_featured,
                   ma.id, :demo_video_url, :metadata::jsonb, :is_active
              FROM storefront_home_sections hs
              LEFT JOIN media_assets ma ON ma.asset_key = :media_asset_key
             WHERE hs.section_key = 'bestsellers'
            ON CONFLICT (item_key) DO UPDATE SET
                family_key     = EXCLUDED.family_key,
                title          = EXCLUDED.title,
                subtitle       = EXCLUDED.subtitle,
                description    = EXCLUDED.description,
                sort_order     = EXCLUDED.sort_order,
                is_featured    = EXCLUDED.is_featured,
                media_asset_id = EXCLUDED.media_asset_id,
                metadata       = EXCLUDED.metadata,
                is_active      = EXCLUDED.is_active,
                updated_at     = now()
            """;
        int n = upsertAll("products/items.json", sql, row -> params(row)
            .addValue("metadata",       jsonb(row, "metadata"))
            .addValue("subtitle",       row.getOrDefault("subtitle",       null))
            .addValue("cta_label",      row.getOrDefault("cta_label",      null))
            .addValue("cta_href",       row.getOrDefault("cta_href",       null))
            .addValue("demo_video_url", row.getOrDefault("demo_video_url", null))
            .addValue("media_asset_key",row.getOrDefault("media_asset_key",null)));
        log.info("[seed] products (storefront_home_items bestsellers): {} upserted", n);
    }

    // =========================================================================
    // Auth — dev/uat login accounts
    // =========================================================================

    private void seedDevAuthAccounts() throws Exception {
        // Ensure uat_seed_accounts exists on DBs where V1 ran before this table was added.
        // CREATE TABLE IF NOT EXISTS is idempotent, so this is safe on every boot.
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE IF NOT EXISTS uat_seed_accounts (
                    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    identity_email   VARCHAR(254) NOT NULL UNIQUE,
                    otp_code         VARCHAR(10)  NOT NULL,
                    customer_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
                    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
                    description      VARCHAR(255),
                    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    CONSTRAINT chk_uat_seed_email_lower
                        CHECK (identity_email = lower(identity_email))
                )
                """);

        // 1. customer_accounts
        int n = upsertAll("auth/dev-accounts.json",
            """
            INSERT INTO customer_accounts (primary_email, display_name, status)
            VALUES (:primary_email, :display_name, COALESCE(:status, 'ACTIVE'))
            ON CONFLICT (primary_email) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                status       = EXCLUDED.status,
                updated_at   = now()
            """,
            row -> params(row));
        log.info("[seed] customer_accounts (dev): {} upserted", n);

        // 2. customer_auth_identities — email identity linked to each seeded account
        int m = upsertAll("auth/dev-accounts.json",
            """
            INSERT INTO customer_auth_identities
                (customer_id, identity_type, identity_value, is_verified, last_verified_at)
            SELECT id, 'EMAIL', primary_email, TRUE, now()
            FROM   customer_accounts
            WHERE  primary_email = :primary_email
            ON CONFLICT (identity_value) DO UPDATE SET
                is_verified      = TRUE,
                last_verified_at = now(),
                updated_at       = now()
            """,
            row -> params(row));
        log.info("[seed] customer_auth_identities (dev): {} upserted", m);

        // 3. uat_seed_accounts — static OTP used by CustomerAuthService in local/dev/uat
        int p = upsertAll("auth/dev-accounts.json",
            """
            INSERT INTO uat_seed_accounts
                (identity_email, otp_code, customer_enabled, is_active, description)
            VALUES (:primary_email, :otp_code, TRUE, TRUE, :description)
            ON CONFLICT (identity_email) DO UPDATE SET
                otp_code         = EXCLUDED.otp_code,
                customer_enabled = EXCLUDED.customer_enabled,
                is_active        = EXCLUDED.is_active,
                description      = EXCLUDED.description,
                updated_at       = now()
            """,
            row -> params(row)
                .addValue("otp_code",     row.getOrDefault("otp_code",     "123456"))
                .addValue("description",  row.getOrDefault("description",  "UAT test account")));
        log.info("[seed] uat_seed_accounts (dev): {} upserted", p);
    }

    // =========================================================================
    // Framework helpers
    // =========================================================================

    @FunctionalInterface
    interface RowMapper {
        MapSqlParameterSource map(Map<String, Object> row) throws Exception;
    }

    /**
     * Reads a JSON file from classpath:db/seed/<path>, parses it as a list of
     * row maps, applies extra params via rowMapper, then executes the SQL for
     * each row.
     *
     * @return total number of rows processed
     */
    private int upsertAll(String seedPath, String sql, RowMapper rowMapper) throws Exception {
        List<Map<String, Object>> rows = readJson("db/seed/" + seedPath);
        int count = 0;
        for (Map<String, Object> row : rows) {
            MapSqlParameterSource params = rowMapper.map(row);
            jdbc.update(sql, params);
            count++;
        }
        return count;
    }

    private List<Map<String, Object>> readJson(String classpathPath) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, LIST_MAP);
        }
    }

    /**
     * Builds a MapSqlParameterSource pre-loaded with all fields from the row map.
     * Converts complex types (Map → JSON string, BigDecimal, etc.) automatically.
     */
    private MapSqlParameterSource params(Map<String, Object> row) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (var entry : row.entrySet()) {
            Object val = entry.getValue();
            // image_path is a local-only field; skip it from SQL params
            if ("image_path".equals(entry.getKey())) continue;
            if (val instanceof Map || val instanceof List) {
                // JSONB field — will be overridden by explicit .addValue in caller if needed
                try {
                    p.addValue(entry.getKey(), objectMapper.writeValueAsString(val));
                } catch (Exception e) {
                    p.addValue(entry.getKey(), val.toString());
                }
            } else if (val instanceof Number n && !(val instanceof Integer) && !(val instanceof Long)) {
                p.addValue(entry.getKey(), new BigDecimal(val.toString()));
            } else {
                p.addValue(entry.getKey(), val);
            }
        }
        return p;
    }

    /**
     * Serialises a field value to a JSONB-compatible string.
     * Explicit call needed when the base params() conversion must be overridden.
     */
    private String jsonb(Map<String, Object> row, String field) {
        Object val = row.get(field);
        if (val == null) return "{}";
        if (val instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(val);
        } catch (Exception e) {
            return "{}";
        }
    }
}

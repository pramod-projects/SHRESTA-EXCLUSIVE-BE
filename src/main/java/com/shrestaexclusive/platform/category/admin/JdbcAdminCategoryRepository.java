package com.shrestaexclusive.platform.category.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdminCategoryRepository implements AdminCategoryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcAdminCategoryRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void createFamily(CategoryFamilyMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_family_config (family_key, display_name, description, sort_order, metadata)
                VALUES (:familyKey, :displayName, :description, COALESCE(:sortOrder, 100), CAST(:metadataJson AS jsonb))
                """, familyParams(request));
    }

    @Override
    public void updateFamily(String familyKey, CategoryFamilyMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_family_config
                SET display_name = COALESCE(:displayName, display_name),
                    description = COALESCE(:description, description),
                    sort_order = COALESCE(:sortOrder, sort_order),
                    metadata = COALESCE(CAST(:metadataJson AS jsonb), metadata),
                    updated_at = now()
                WHERE family_key = :targetFamilyKey
                """, familyParams(request).addValue("targetFamilyKey", familyKey));
    }

    @Override
    public void archiveFamily(String familyKey) {
        jdbcTemplate.update("""
                UPDATE category_family_config
                SET is_active = FALSE, updated_at = now()
                WHERE family_key = :familyKey
                """, new MapSqlParameterSource("familyKey", familyKey));
    }

    @Override
    public void deleteFamily(String familyKey) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("familyKey", familyKey);
        jdbcTemplate.update("""
                UPDATE media_assets
                SET category_family_key = NULL, updated_at = now()
                WHERE category_family_key = :familyKey
                """, parameters);
        jdbcTemplate.update("""
                UPDATE storefront_home_items
                SET family_key = NULL, updated_at = now()
                WHERE family_key = :familyKey
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_filter_config
                WHERE family_id = (SELECT id FROM category_family_config WHERE family_key = :familyKey)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_attribute_config
                WHERE family_id = (SELECT id FROM category_family_config WHERE family_key = :familyKey)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_product_type_config
                WHERE family_id = (SELECT id FROM category_family_config WHERE family_key = :familyKey)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_tax_config
                WHERE family_id = (SELECT id FROM category_family_config WHERE family_key = :familyKey)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_styling_config
                WHERE family_id = (SELECT id FROM category_family_config WHERE family_key = :familyKey)
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_family_config
                WHERE family_key = :familyKey
                """, parameters);
    }

    @Override
    public void createProductType(String familyKey, CategoryProductTypeMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_product_type_config (family_id, type_key, display_name, sort_order, metadata)
                SELECT id, :typeKey, :displayName, COALESCE(:sortOrder, 100), CAST(:metadataJson AS jsonb)
                FROM category_family_config
                WHERE family_key = :familyKey AND is_active = TRUE
                """, typeParams(request).addValue("familyKey", familyKey));
    }

    @Override
    public void updateProductType(String familyKey, String typeKey, CategoryProductTypeMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_product_type_config product_type
                SET display_name = COALESCE(:displayName, product_type.display_name),
                    sort_order = COALESCE(:sortOrder, product_type.sort_order),
                    metadata = COALESCE(CAST(:metadataJson AS jsonb), product_type.metadata),
                    updated_at = now()
                FROM category_family_config family
                WHERE product_type.family_id = family.id
                  AND family.family_key = :familyKey
                  AND product_type.type_key = :typeKey
                """, typeParams(request)
                .addValue("familyKey", familyKey)
                .addValue("typeKey", typeKey));
    }

    @Override
    public void archiveProductType(String familyKey, String typeKey) {
        jdbcTemplate.update("""
                UPDATE category_product_type_config product_type
                SET is_active = FALSE, updated_at = now()
                FROM category_family_config family
                WHERE product_type.family_id = family.id
                  AND family.family_key = :familyKey
                  AND product_type.type_key = :typeKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("typeKey", typeKey));
    }

    @Override
    public void deleteProductType(String familyKey, String typeKey) {
        jdbcTemplate.update("""
                DELETE FROM category_product_type_config product_type
                USING category_family_config family
                WHERE product_type.family_id = family.id
                  AND family.family_key = :familyKey
                  AND product_type.type_key = :typeKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("typeKey", typeKey));
    }

    @Override
    public void createAttribute(String familyKey, CategoryAttributeMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_attribute_config (
                    family_id, attribute_key, display_name, data_type, is_required,
                    is_filterable, is_searchable, allowed_values, sort_order
                )
                SELECT id, :attributeKey, :displayName, :dataType, COALESCE(:required, FALSE),
                       COALESCE(:filterable, FALSE), COALESCE(:searchable, FALSE),
                       COALESCE(CAST(:allowedValuesJson AS jsonb), '[]'::jsonb),
                       COALESCE(:sortOrder, 100)
                FROM category_family_config
                WHERE family_key = :familyKey AND is_active = TRUE
                """, attributeParams(request).addValue("familyKey", familyKey));
    }

    @Override
    public void updateAttribute(String familyKey, String attributeKey, CategoryAttributeMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_attribute_config attribute
                SET display_name = COALESCE(:displayName, attribute.display_name),
                    data_type = COALESCE(:dataType, attribute.data_type),
                    is_required = COALESCE(:required, attribute.is_required),
                    is_filterable = COALESCE(:filterable, attribute.is_filterable),
                    is_searchable = COALESCE(:searchable, attribute.is_searchable),
                    allowed_values = COALESCE(CAST(:allowedValuesJson AS jsonb), attribute.allowed_values),
                    sort_order = COALESCE(:sortOrder, attribute.sort_order),
                    updated_at = now()
                FROM category_family_config family
                WHERE attribute.family_id = family.id
                  AND family.family_key = :familyKey
                  AND attribute.attribute_key = :attributeKey
                """, attributeParams(request)
                .addValue("familyKey", familyKey)
                .addValue("attributeKey", attributeKey));
    }

    @Override
    public void archiveAttribute(String familyKey, String attributeKey) {
        jdbcTemplate.update("""
                UPDATE category_attribute_config attribute
                SET is_active = FALSE, updated_at = now()
                FROM category_family_config family
                WHERE attribute.family_id = family.id
                  AND family.family_key = :familyKey
                  AND attribute.attribute_key = :attributeKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("attributeKey", attributeKey));
    }

    @Override
    public void deleteAttribute(String familyKey, String attributeKey) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("attributeKey", attributeKey);
        jdbcTemplate.update("""
                DELETE FROM category_filter_config filter_config
                USING category_family_config family, category_attribute_config attribute
                WHERE filter_config.family_id = family.id
                  AND filter_config.attribute_id = attribute.id
                  AND attribute.family_id = family.id
                  AND family.family_key = :familyKey
                  AND attribute.attribute_key = :attributeKey
                """, parameters);
        jdbcTemplate.update("""
                DELETE FROM category_attribute_config attribute
                USING category_family_config family
                WHERE attribute.family_id = family.id
                  AND family.family_key = :familyKey
                  AND attribute.attribute_key = :attributeKey
                """, parameters);
    }

    @Override
    public void createFilter(String familyKey, CategoryFilterMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_filter_config (
                    family_id, attribute_id, filter_key, display_name, frontend_control,
                    backend_mapping, sort_order
                )
                SELECT family.id, attribute.id, :filterKey, :displayName, :frontendControl,
                       :backendMapping, COALESCE(:sortOrder, 100)
                FROM category_family_config family
                JOIN category_attribute_config attribute ON attribute.family_id = family.id
                WHERE family.family_key = :familyKey
                  AND family.is_active = TRUE
                  AND attribute.attribute_key = :attributeKey
                  AND attribute.is_active = TRUE
                """, filterParams(request).addValue("familyKey", familyKey));
    }

    @Override
    public void updateFilter(String familyKey, String filterKey, CategoryFilterMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_filter_config filter_config
                SET display_name = COALESCE(:displayName, filter_config.display_name),
                    attribute_id = COALESCE((
                        SELECT attribute.id
                        FROM category_family_config family_for_attribute
                        JOIN category_attribute_config attribute ON attribute.family_id = family_for_attribute.id
                        WHERE family_for_attribute.family_key = :familyKey
                          AND attribute.attribute_key = :attributeKey
                          AND attribute.is_active = TRUE
                    ), filter_config.attribute_id),
                    frontend_control = COALESCE(:frontendControl, filter_config.frontend_control),
                    backend_mapping = COALESCE(:backendMapping, filter_config.backend_mapping),
                    sort_order = COALESCE(:sortOrder, filter_config.sort_order),
                    updated_at = now()
                FROM category_family_config family
                WHERE filter_config.family_id = family.id
                  AND family.family_key = :familyKey
                  AND filter_config.filter_key = :filterKey
                """, filterParams(request)
                .addValue("familyKey", familyKey)
                .addValue("filterKey", filterKey));
    }

    @Override
    public void archiveFilter(String familyKey, String filterKey) {
        jdbcTemplate.update("""
                UPDATE category_filter_config filter_config
                SET is_active = FALSE, updated_at = now()
                FROM category_family_config family
                WHERE filter_config.family_id = family.id
                  AND family.family_key = :familyKey
                  AND filter_config.filter_key = :filterKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("filterKey", filterKey));
    }

    @Override
    public void deleteFilter(String familyKey, String filterKey) {
        jdbcTemplate.update("""
                DELETE FROM category_filter_config filter_config
                USING category_family_config family
                WHERE filter_config.family_id = family.id
                  AND family.family_key = :familyKey
                  AND filter_config.filter_key = :filterKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("filterKey", filterKey));
    }

    @Override
    public void createTax(String familyKey, CategoryTaxMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_tax_config (family_id, hsn_code, gst_rate_basis_points, effective_from, effective_to)
                SELECT id, :hsnCode, :gstRateBasisPoints, :effectiveFrom, :effectiveTo
                FROM category_family_config
                WHERE family_key = :familyKey AND is_active = TRUE
                """, taxParams(request).addValue("familyKey", familyKey));
    }

    @Override
    public void updateTax(String familyKey, String hsnCode, LocalDate effectiveFrom, CategoryTaxMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_tax_config tax
                SET hsn_code = COALESCE(:hsnCode, tax.hsn_code),
                    gst_rate_basis_points = COALESCE(:gstRateBasisPoints, tax.gst_rate_basis_points),
                    effective_from = COALESCE(:effectiveFrom, tax.effective_from),
                    effective_to = CASE
                        WHEN :clearEffectiveTo = TRUE THEN NULL
                        WHEN :effectiveTo IS NULL THEN tax.effective_to
                        ELSE :effectiveTo
                    END,
                    updated_at = now()
                FROM category_family_config family
                WHERE tax.family_id = family.id
                  AND family.family_key = :familyKey
                  AND tax.hsn_code = :targetHsnCode
                  AND tax.effective_from = :targetEffectiveFrom
                """, taxParams(request)
                .addValue("familyKey", familyKey)
                .addValue("targetHsnCode", hsnCode)
                .addValue("targetEffectiveFrom", effectiveFrom));
    }

    @Override
    public void archiveTax(String familyKey, String hsnCode, LocalDate effectiveFrom) {
        jdbcTemplate.update("""
                UPDATE category_tax_config tax
                SET is_active = FALSE, updated_at = now()
                FROM category_family_config family
                WHERE tax.family_id = family.id
                  AND family.family_key = :familyKey
                  AND tax.hsn_code = :hsnCode
                  AND tax.effective_from = :effectiveFrom
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("hsnCode", hsnCode)
                .addValue("effectiveFrom", effectiveFrom));
    }

    @Override
    public void deleteTax(String familyKey, String hsnCode, LocalDate effectiveFrom) {
        jdbcTemplate.update("""
                DELETE FROM category_tax_config tax
                USING category_family_config family
                WHERE tax.family_id = family.id
                  AND family.family_key = :familyKey
                  AND tax.hsn_code = :hsnCode
                  AND tax.effective_from = :effectiveFrom
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("hsnCode", hsnCode)
                .addValue("effectiveFrom", effectiveFrom));
    }

    @Override
    public void createStyling(String familyKey, CategoryStylingMutationRequest request) {
        jdbcTemplate.update("""
                INSERT INTO category_styling_config (
                    family_id, occasion_key, display_name, complementary_family_keys, rules, sort_order
                )
                SELECT id, :occasionKey, :displayName,
                       COALESCE(CAST(:complementaryFamilyKeysJson AS jsonb), '[]'::jsonb),
                       COALESCE(CAST(:rulesJson AS jsonb), '{}'::jsonb),
                       COALESCE(:sortOrder, 100)
                FROM category_family_config
                WHERE family_key = :familyKey AND is_active = TRUE
                """, stylingParams(request).addValue("familyKey", familyKey));
    }

    @Override
    public void updateStyling(String familyKey, String occasionKey, CategoryStylingMutationRequest request) {
        jdbcTemplate.update("""
                UPDATE category_styling_config styling
                SET display_name = COALESCE(:displayName, styling.display_name),
                    complementary_family_keys = COALESCE(CAST(:complementaryFamilyKeysJson AS jsonb), styling.complementary_family_keys),
                    rules = COALESCE(CAST(:rulesJson AS jsonb), styling.rules),
                    sort_order = COALESCE(:sortOrder, styling.sort_order),
                    updated_at = now()
                FROM category_family_config family
                WHERE styling.family_id = family.id
                  AND family.family_key = :familyKey
                  AND styling.occasion_key = :occasionKey
                """, stylingParams(request)
                .addValue("familyKey", familyKey)
                .addValue("occasionKey", occasionKey));
    }

    @Override
    public void archiveStyling(String familyKey, String occasionKey) {
        jdbcTemplate.update("""
                UPDATE category_styling_config styling
                SET is_active = FALSE, updated_at = now()
                FROM category_family_config family
                WHERE styling.family_id = family.id
                  AND family.family_key = :familyKey
                  AND styling.occasion_key = :occasionKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("occasionKey", occasionKey));
    }

    @Override
    public void deleteStyling(String familyKey, String occasionKey) {
        jdbcTemplate.update("""
                DELETE FROM category_styling_config styling
                USING category_family_config family
                WHERE styling.family_id = family.id
                  AND family.family_key = :familyKey
                  AND styling.occasion_key = :occasionKey
                """, new MapSqlParameterSource()
                .addValue("familyKey", familyKey)
                .addValue("occasionKey", occasionKey));
    }

    private MapSqlParameterSource familyParams(CategoryFamilyMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("familyKey", request.familyKey())
                .addValue("displayName", request.displayName())
                .addValue("description", request.description())
                .addValue("sortOrder", request.sortOrder())
                .addValue("metadataJson", jsonOrDefault(request.metadata()));
    }

    private MapSqlParameterSource typeParams(CategoryProductTypeMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("typeKey", request.typeKey())
                .addValue("displayName", request.displayName())
                .addValue("sortOrder", request.sortOrder())
                .addValue("metadataJson", jsonOrDefault(request.metadata()));
    }

    private MapSqlParameterSource attributeParams(CategoryAttributeMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("attributeKey", request.attributeKey())
                .addValue("displayName", request.displayName())
                .addValue("dataType", request.dataType())
                .addValue("required", request.required())
                .addValue("filterable", request.filterable())
                .addValue("searchable", request.searchable())
                .addValue("allowedValuesJson", jsonListOrNull(request.allowedValues()))
                .addValue("sortOrder", request.sortOrder());
    }

    private MapSqlParameterSource filterParams(CategoryFilterMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("filterKey", request.filterKey())
                .addValue("displayName", request.displayName())
                .addValue("attributeKey", request.attributeKey())
                .addValue("frontendControl", request.frontendControl())
                .addValue("backendMapping", request.backendMapping())
                .addValue("sortOrder", request.sortOrder());
    }

    private MapSqlParameterSource taxParams(CategoryTaxMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("hsnCode", request.hsnCode())
                .addValue("gstRateBasisPoints", request.gstRateBasisPoints())
                .addValue("effectiveFrom", request.effectiveFrom())
                .addValue("effectiveTo", request.effectiveTo())
                .addValue("clearEffectiveTo", Boolean.TRUE.equals(request.clearEffectiveTo()));
    }

    private MapSqlParameterSource stylingParams(CategoryStylingMutationRequest request) {
        return new MapSqlParameterSource()
                .addValue("occasionKey", request.occasionKey())
                .addValue("displayName", request.displayName())
                .addValue("complementaryFamilyKeysJson", jsonListOrNull(request.complementaryFamilyKeys()))
                .addValue("rulesJson", jsonOrDefault(request.rules()))
                .addValue("sortOrder", request.sortOrder());
    }

    private String jsonOrDefault(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        if (metadata.isEmpty()) {
            return "{}";
        }

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid category metadata", exception);
        }
    }

    private String jsonListOrNull(List<String> values) {
        if (values == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid category list value", exception);
        }
    }
}

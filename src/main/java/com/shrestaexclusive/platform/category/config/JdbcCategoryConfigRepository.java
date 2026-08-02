package com.shrestaexclusive.platform.category.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCategoryConfigRepository implements CategoryConfigRepository {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcCategoryConfigRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FamilyRow> findActiveFamilies() {
        return jdbcTemplate.query("""
                SELECT id, family_key, display_name, description, sort_order, metadata
                FROM category_family_config
                WHERE is_active = TRUE
                ORDER BY sort_order, display_name
                """, (rs, rowNum) -> new FamilyRow(
                uuid(rs, "id"),
                rs.getString("family_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getInt("sort_order"),
                jsonObject(rs, "metadata")
        ));
    }

    @Override
    public List<ProductTypeRow> findActiveProductTypes(List<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query("""
                SELECT family_id, type_key, display_name, sort_order, metadata
                FROM category_product_type_config
                WHERE is_active = TRUE AND family_id IN (:familyIds)
                ORDER BY family_id, sort_order, display_name
                """, familyIds(familyIds), (rs, rowNum) -> new ProductTypeRow(
                uuid(rs, "family_id"),
                rs.getString("type_key"),
                rs.getString("display_name"),
                rs.getInt("sort_order"),
                jsonObject(rs, "metadata")
        ));
    }

    @Override
    public List<AttributeRow> findAttributes(List<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query("""
                SELECT family_id, attribute_key, display_name, data_type, is_required,
                       is_filterable, is_searchable, allowed_values, sort_order
                FROM category_attribute_config
                WHERE is_active = TRUE AND family_id IN (:familyIds)
                ORDER BY family_id, sort_order, display_name
                """, familyIds(familyIds), (rs, rowNum) -> new AttributeRow(
                uuid(rs, "family_id"),
                rs.getString("attribute_key"),
                rs.getString("display_name"),
                rs.getString("data_type"),
                rs.getBoolean("is_required"),
                rs.getBoolean("is_filterable"),
                rs.getBoolean("is_searchable"),
                jsonStringList(rs, "allowed_values"),
                rs.getInt("sort_order")
        ));
    }

    @Override
    public List<FilterRow> findActiveFilters(List<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query("""
                SELECT filter_config.family_id, filter_config.filter_key, filter_config.display_name,
                       attribute.attribute_key, filter_config.frontend_control,
                       filter_config.backend_mapping, filter_config.sort_order
                FROM category_filter_config filter_config
                JOIN category_attribute_config attribute ON attribute.id = filter_config.attribute_id
                WHERE filter_config.is_active = TRUE
                  AND attribute.is_active = TRUE
                  AND filter_config.family_id IN (:familyIds)
                ORDER BY filter_config.family_id, filter_config.sort_order, filter_config.display_name
                """, familyIds(familyIds), (rs, rowNum) -> new FilterRow(
                uuid(rs, "family_id"),
                rs.getString("filter_key"),
                rs.getString("display_name"),
                rs.getString("attribute_key"),
                rs.getString("frontend_control"),
                rs.getString("backend_mapping"),
                rs.getInt("sort_order")
        ));
    }

    @Override
    public List<TaxRow> findActiveTaxes(List<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query("""
                SELECT family_id, hsn_code, gst_rate_basis_points, effective_from, effective_to
                FROM category_tax_config
                WHERE is_active = TRUE AND family_id IN (:familyIds)
                ORDER BY family_id, effective_from DESC, hsn_code
                """, familyIds(familyIds), (rs, rowNum) -> new TaxRow(
                uuid(rs, "family_id"),
                rs.getString("hsn_code"),
                rs.getInt("gst_rate_basis_points"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class)
        ));
    }

    @Override
    public List<StylingRow> findActiveStyling(List<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query("""
                SELECT family_id, occasion_key, display_name,
                       complementary_family_keys, rules, sort_order
                FROM category_styling_config
                WHERE is_active = TRUE AND family_id IN (:familyIds)
                ORDER BY family_id, sort_order, display_name
                """, familyIds(familyIds), (rs, rowNum) -> new StylingRow(
                uuid(rs, "family_id"),
                rs.getString("occasion_key"),
                rs.getString("display_name"),
                jsonStringList(rs, "complementary_family_keys"),
                jsonObject(rs, "rules"),
                rs.getInt("sort_order")
        ));
    }

    private MapSqlParameterSource familyIds(List<UUID> familyIds) {
        return new MapSqlParameterSource("familyIds", familyIds);
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
            throw new SQLException("Invalid JSON string list in column " + column, exception);
        }
    }
}

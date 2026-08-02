package com.shrestaexclusive.platform.storefront.stores;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.OpeningHourRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.SectionRow;
import com.shrestaexclusive.platform.storefront.stores.StorefrontStoresRepository.StoreRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStorefrontStoresRepository implements StorefrontStoresRepository {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<OpeningHourRow>> OPENING_HOUR_LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcStorefrontStoresRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SectionRow> findActiveSection(String sectionKey) {
        List<SectionRow> sections = jdbcTemplate.query("""
                SELECT id, section_key, eyebrow, title, description, metadata
                FROM storefront_store_sections
                WHERE section_key = :sectionKey AND is_active = TRUE
                ORDER BY sort_order
                LIMIT 1
                """, new MapSqlParameterSource("sectionKey", sectionKey), (rs, rowNum) -> new SectionRow(
                uuid(rs, "id"),
                rs.getString("section_key"),
                rs.getString("eyebrow"),
                rs.getString("title"),
                rs.getString("description"),
                jsonObject(rs, "metadata")
        ));
        return sections.stream().findFirst();
    }

    @Override
    public List<StoreRow> findActiveStores() {
        return jdbcTemplate.query("""
                SELECT id, store_key, display_name, short_name, status,
                       address_line1, address_line2, locality, city, state, postal_code, country_code,
                       phone, whatsapp_number, email, latitude, longitude,
                       supported_family_keys, service_modes, highlights, opening_hours, fulfillment,
                       sort_order
                FROM store_locations
                WHERE is_active = TRUE
                ORDER BY sort_order, city, display_name
                """, (rs, rowNum) -> new StoreRow(
                uuid(rs, "id"),
                rs.getString("store_key"),
                rs.getString("display_name"),
                rs.getString("short_name"),
                rs.getString("status"),
                rs.getString("address_line1"),
                rs.getString("address_line2"),
                rs.getString("locality"),
                rs.getString("city"),
                rs.getString("state"),
                rs.getString("postal_code"),
                rs.getString("country_code"),
                rs.getString("phone"),
                rs.getString("whatsapp_number"),
                rs.getString("email"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                jsonStringList(rs, "supported_family_keys"),
                jsonStringList(rs, "service_modes"),
                jsonStringList(rs, "highlights"),
                jsonOpeningHours(rs, "opening_hours"),
                jsonObject(rs, "fulfillment"),
                rs.getInt("sort_order")
        ));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private Map<String, Object> jsonObject(ResultSet rs, String column) throws SQLException {
        return json(rs, column, STRING_OBJECT_MAP, Map.of());
    }

    private List<String> jsonStringList(ResultSet rs, String column) throws SQLException {
        return json(rs, column, STRING_LIST, List.of());
    }

    private List<OpeningHourRow> jsonOpeningHours(ResultSet rs, String column) throws SQLException {
        return json(rs, column, OPENING_HOUR_LIST, List.of());
    }

    private <T> T json(ResultSet rs, String column, TypeReference<T> type, T defaultValue) throws SQLException {
        String rawJson = rs.getString(column);
        if (rawJson == null || rawJson.isBlank()) {
            return defaultValue;
        }

        try {
            return objectMapper.readValue(rawJson, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid JSON in column " + column, exception);
        }
    }
}

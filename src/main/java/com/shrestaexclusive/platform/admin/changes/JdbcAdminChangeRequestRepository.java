package com.shrestaexclusive.platform.admin.changes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdminChangeRequestRepository implements AdminChangeRequestRepository {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcAdminChangeRequestRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AdminChangeRequestResponse create(String requestKey, String submittedByRole, AdminChangeRequestCreateRequest request) {
        jdbcTemplate.update("""
                INSERT INTO admin_change_requests (
                    request_key, request_type, entity_type, entity_key, action,
                    submitted_by_role, submitted_by, payload
                )
                VALUES (
                    :requestKey, :requestType, :entityType, :entityKey, :action,
                    :submittedByRole, :submittedBy, CAST(:payloadJson AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("requestKey", requestKey)
                .addValue("requestType", request.requestType())
                .addValue("entityType", request.entityType())
                .addValue("entityKey", request.entityKey())
                .addValue("action", request.action())
                .addValue("submittedByRole", submittedByRole)
                .addValue("submittedBy", request.submittedBy())
                .addValue("payloadJson", json(request.payload() == null ? Map.of() : request.payload())));

        return findByRequestKey(requestKey).orElseThrow();
    }

    @Override
    public List<AdminChangeRequestResponse> list(String status) {
        return jdbcTemplate.query("""
                SELECT request_key, request_type, entity_type, entity_key, action, status,
                       submitted_by_role, submitted_by, reviewed_by_role, reviewed_by, review_note,
                       payload, created_at, updated_at, reviewed_at
                FROM admin_change_requests
                WHERE CAST(:status AS text) IS NULL OR status = CAST(:status AS text)
                ORDER BY created_at DESC
                LIMIT 200
                """, new MapSqlParameterSource("status", status), this::row);
    }

    @Override
    public Optional<AdminChangeRequestResponse> findByRequestKey(String requestKey) {
        return jdbcTemplate.query("""
                SELECT request_key, request_type, entity_type, entity_key, action, status,
                       submitted_by_role, submitted_by, reviewed_by_role, reviewed_by, review_note,
                       payload, created_at, updated_at, reviewed_at
                FROM admin_change_requests
                WHERE request_key = :requestKey
                """, new MapSqlParameterSource("requestKey", requestKey), this::row).stream().findFirst();
    }

    @Override
    public AdminChangeRequestResponse upsertPending(String newRequestKey, String submittedByRole, AdminChangeRequestCreateRequest request) {
        // Try to update an existing PENDING_REVIEW request for this (entity_key, request_type) first
        int updated = jdbcTemplate.update("""
                UPDATE admin_change_requests
                SET submitted_by_role = :submittedByRole,
                    submitted_by      = :submittedBy,
                    payload           = CAST(:payloadJson AS jsonb),
                    updated_at        = now()
                WHERE entity_key   = :entityKey
                  AND request_type = :requestType
                  AND status       = 'PENDING_REVIEW'
                """, new MapSqlParameterSource()
                .addValue("submittedByRole", submittedByRole)
                .addValue("submittedBy", request.submittedBy())
                .addValue("payloadJson", json(request.payload() == null ? Map.of() : request.payload()))
                .addValue("entityKey", request.entityKey())
                .addValue("requestType", request.requestType()));

        if (updated > 0) {
            return findFirstPending(request.entityKey(), request.requestType()).orElseThrow();
        }
        // No existing pending request — create fresh
        return create(newRequestKey, submittedByRole, request);
    }

    @Override
    public Optional<AdminChangeRequestResponse> findFirstPending(String entityKey, String requestType) {
        return jdbcTemplate.query("""
                SELECT request_key, request_type, entity_type, entity_key, action, status,
                       submitted_by_role, submitted_by, reviewed_by_role, reviewed_by, review_note,
                       payload, created_at, updated_at, reviewed_at
                FROM admin_change_requests
                WHERE entity_key   = :entityKey
                  AND request_type = :requestType
                  AND status       = 'PENDING_REVIEW'
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("entityKey", entityKey)
                .addValue("requestType", requestType), this::row)
                .stream().findFirst();
    }

    @Override
    public AdminChangeRequestResponse approve(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request) {
        return decide(requestKey, reviewerRole, request, "APPROVED");
    }

    @Override
    public AdminChangeRequestResponse reject(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request) {
        return decide(requestKey, reviewerRole, request, "REJECTED");
    }

    private AdminChangeRequestResponse decide(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request, String status) {
        jdbcTemplate.update("""
                UPDATE admin_change_requests
                SET status = :status,
                    reviewed_by_role = :reviewerRole,
                    reviewed_by = :reviewedBy,
                    review_note = :reviewNote,
                    reviewed_at = now(),
                    updated_at = now()
                WHERE request_key = :requestKey AND status = 'PENDING_REVIEW'
                """, new MapSqlParameterSource()
                .addValue("requestKey", requestKey)
                .addValue("status", status)
                .addValue("reviewerRole", reviewerRole)
                .addValue("reviewedBy", request.reviewedBy())
                .addValue("reviewNote", request.reviewNote()));

        return findByRequestKey(requestKey).orElseThrow();
    }

    private AdminChangeRequestResponse row(ResultSet rs, int rowNum) throws SQLException {
        return new AdminChangeRequestResponse(
                rs.getString("request_key"),
                rs.getString("request_type"),
                rs.getString("entity_type"),
                rs.getString("entity_key"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("submitted_by_role"),
                rs.getString("submitted_by"),
                rs.getString("reviewed_by_role"),
                rs.getString("reviewed_by"),
                rs.getString("review_note"),
                jsonObject(rs, "payload"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "reviewed_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
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

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid change request payload", exception);
        }
    }
}

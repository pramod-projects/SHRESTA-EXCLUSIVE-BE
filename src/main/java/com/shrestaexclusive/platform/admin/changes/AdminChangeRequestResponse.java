package com.shrestaexclusive.platform.admin.changes;

import java.time.Instant;
import java.util.Map;

public record AdminChangeRequestResponse(
        String requestKey,
        String requestType,
        String entityType,
        String entityKey,
        String action,
        String status,
        String submittedByRole,
        String submittedBy,
        String reviewedByRole,
        String reviewedBy,
        String reviewNote,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt,
        Instant reviewedAt
) {
}

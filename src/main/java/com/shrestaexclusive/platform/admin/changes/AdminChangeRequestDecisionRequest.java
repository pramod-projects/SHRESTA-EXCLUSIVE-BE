package com.shrestaexclusive.platform.admin.changes;

import jakarta.validation.constraints.Size;

public record AdminChangeRequestDecisionRequest(
        @Size(max = 160) String reviewedBy,
        @Size(max = 2000) String reviewNote
) {
}

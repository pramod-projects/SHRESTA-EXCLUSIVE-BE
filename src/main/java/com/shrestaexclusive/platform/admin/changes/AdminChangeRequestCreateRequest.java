package com.shrestaexclusive.platform.admin.changes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AdminChangeRequestCreateRequest(
        @NotBlank @Size(max = 80) String requestType,
        @NotBlank @Size(max = 80) String entityType,
        @NotBlank @Size(max = 180) String entityKey,
        @NotBlank @Pattern(regexp = "CREATE|UPDATE|ARCHIVE|DELETE") String action,
        @Size(max = 160) String submittedBy,
        Map<String, Object> payload
) {
}

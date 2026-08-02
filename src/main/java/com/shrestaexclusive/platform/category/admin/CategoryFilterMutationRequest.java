package com.shrestaexclusive.platform.category.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CategoryFilterMutationRequest(
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String filterKey,
        @Size(max = 120) String displayName,
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String attributeKey,
        @Pattern(regexp = "^(checkbox|radio|range|toggle|swatch)$") String frontendControl,
        @Size(max = 120) String backendMapping,
        @Min(0) Integer sortOrder
) {
}

package com.shrestaexclusive.platform.category.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CategoryFamilyMutationRequest(
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String familyKey,
        @Size(max = 120) String displayName,
        @Size(max = 2000) String description,
        @Min(0) Integer sortOrder,
        Map<String, Object> metadata
) {
}

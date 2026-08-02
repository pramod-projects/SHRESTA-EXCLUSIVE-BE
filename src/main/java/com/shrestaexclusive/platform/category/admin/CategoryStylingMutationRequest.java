package com.shrestaexclusive.platform.category.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record CategoryStylingMutationRequest(
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String occasionKey,
        @Size(max = 120) String displayName,
        List<@Pattern(regexp = "^[a-z][a-z0-9_]*$") String> complementaryFamilyKeys,
        Map<String, Object> rules,
        @Min(0) Integer sortOrder
) {
}

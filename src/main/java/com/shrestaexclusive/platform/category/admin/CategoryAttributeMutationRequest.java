package com.shrestaexclusive.platform.category.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CategoryAttributeMutationRequest(
        @Pattern(regexp = "^[a-z][a-z0-9_]*$") String attributeKey,
        @Size(max = 120) String displayName,
        @Pattern(regexp = "^(string|integer|boolean|decimal|enum|multi_enum)$") String dataType,
        Boolean required,
        Boolean filterable,
        Boolean searchable,
        List<@Size(max = 80) String> allowedValues,
        @Min(0) Integer sortOrder
) {
}

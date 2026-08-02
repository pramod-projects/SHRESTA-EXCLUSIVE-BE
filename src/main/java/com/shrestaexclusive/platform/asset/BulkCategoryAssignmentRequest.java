package com.shrestaexclusive.platform.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BulkCategoryAssignmentRequest(
        @NotEmpty List<String> assetKeys,
        @NotBlank String categoryFamilyKey,
        @Size(max = 80) String categoryProductTypeKey
) {
}

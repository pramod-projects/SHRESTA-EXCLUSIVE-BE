package com.shrestaexclusive.platform.category.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CategoryTaxMutationRequest(
        @Size(max = 32) String hsnCode,
        @Min(0) @Max(2800) Integer gstRateBasisPoints,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Boolean clearEffectiveTo
) {
}

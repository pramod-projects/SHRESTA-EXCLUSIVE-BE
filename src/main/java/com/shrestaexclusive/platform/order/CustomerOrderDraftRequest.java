package com.shrestaexclusive.platform.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CustomerOrderDraftRequest(
        @NotEmpty @Size(max = 100) List<@Valid LineItem> lines
) {

    public record LineItem(
            @NotBlank @Size(max = 120) String productId,
            @Min(1) @Max(99) int quantity
    ) {
    }
}

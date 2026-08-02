package com.shrestaexclusive.platform.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CustomerOrderPlacementRequest(
        @NotEmpty @Size(max = 100) List<@Valid LineItem> lines,
        @Size(max = 80) String draftOrderId,
        @Valid @NotNull Contact contact,
        @Valid @NotNull ShippingAddress shippingAddress,
        @NotBlank @Pattern(regexp = "^(STANDARD|EXPRESS|SAME_DAY)$") String deliveryMode,
        @NotBlank @Pattern(regexp = "^(UPI|CARD|NETBANKING)$") String paymentMethod,
        @AssertTrue boolean acceptedTerms
) {

    public record LineItem(
            @NotBlank @Size(max = 120) String productId,
            @Min(1) @Max(99) int quantity
    ) {
    }

    public record Contact(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "^[6-9][0-9]{9}$") String phone
    ) {
    }

    public record ShippingAddress(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Pattern(regexp = "^[6-9][0-9]{9}$") String phone,
            @NotBlank @Size(max = 240) String addressLine1,
            @Size(max = 240) String addressLine2,
            @Size(max = 160) String landmark,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String state,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String postalCode,
            @NotBlank @Size(max = 80) String country,
            @NotBlank @Pattern(regexp = "^(HOME|WORK|OTHER)$") String addressType
    ) {
    }
}

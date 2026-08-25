package com.shrestaexclusive.platform.asset;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record MediaUploadAuthorizationRequest(
        String productId,
        @NotBlank @Pattern(regexp = "PRODUCT_IMAGE|PRODUCT_VIDEO|DISPLAY_IMAGE|DISPLAY_VIDEO") String mediaType,
        @NotBlank String contentType,
        @NotBlank String originalFilename,
        @Positive long byteSize,
        @Min(1) Integer widthPx,
        @Min(1) Integer heightPx,
        @Min(0) @Max(30) Integer durationSeconds,
        String altText
) {
}
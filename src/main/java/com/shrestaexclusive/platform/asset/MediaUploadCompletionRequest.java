package com.shrestaexclusive.platform.asset;

import jakarta.validation.constraints.NotBlank;

public record MediaUploadCompletionRequest(@NotBlank String mediaId) {
}
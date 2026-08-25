package com.shrestaexclusive.platform.admin.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUserCreateRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 120) String password,
        @NotBlank @Pattern(regexp = "^(CHANGE_SUBMITTER|CHANGE_APPROVER|CHANGE_MANAGER|CHANGE_ADMIN)$") String role
) {
}

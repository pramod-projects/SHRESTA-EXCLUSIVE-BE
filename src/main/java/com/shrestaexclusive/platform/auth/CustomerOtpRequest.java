package com.shrestaexclusive.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomerOtpRequest(
        @NotBlank
        @Pattern(regexp = "^(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:\\+91[ -]?)?[6-9][0-9]{9})$")
        String identity
) {
}

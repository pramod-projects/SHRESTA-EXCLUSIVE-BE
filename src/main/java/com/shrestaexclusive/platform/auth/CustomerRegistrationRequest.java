package com.shrestaexclusive.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomerRegistrationRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z .'-]{1,79}$")
        String firstName,

        @Pattern(regexp = "^(?:[A-Za-z][A-Za-z .'-]{1,79})?$")
        String middleName,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z .'-]{1,79}$")
        String lastName,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        String email,

        @NotBlank
        @Pattern(regexp = "^(?:\\+91[ -]?)?[6-9][0-9]{9}$")
        String mobile,

        @Pattern(regexp = "^[0-9]{6}$")
        String otp
) {
}

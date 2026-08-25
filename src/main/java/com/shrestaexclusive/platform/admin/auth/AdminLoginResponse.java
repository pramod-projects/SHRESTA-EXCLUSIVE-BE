package com.shrestaexclusive.platform.admin.auth;

import java.util.List;

public record AdminLoginResponse(
        String email,
        String role,
        List<String> permissions
) {
}

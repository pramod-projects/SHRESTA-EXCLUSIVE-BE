package com.shrestaexclusive.platform.admin.acl;

import java.util.List;

public record AdminAclResponse(
        String role,
        List<String> permissions
) {

    public AdminAclResponse {
        permissions = List.copyOf(permissions);
    }
}

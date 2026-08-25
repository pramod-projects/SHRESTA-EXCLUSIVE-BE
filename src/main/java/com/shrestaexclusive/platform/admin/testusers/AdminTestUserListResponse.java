package com.shrestaexclusive.platform.admin.testusers;

import java.util.List;

public record AdminTestUserListResponse(
        List<AdminTestUserItem> items,
        int page,
        int size,
        long total
) {
}

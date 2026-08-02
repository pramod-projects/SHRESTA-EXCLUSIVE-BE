package com.shrestaexclusive.platform.support.chat;

import java.time.Instant;
import java.util.List;

public record CustomerChatMessageResponse(
        String conversationId,
        String assistantMessage,
        List<String> quickActions,
        boolean escalationSuggested,
        Instant timestamp
) {
}

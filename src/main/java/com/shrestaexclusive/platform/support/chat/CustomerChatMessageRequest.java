package com.shrestaexclusive.platform.support.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerChatMessageRequest(
        String conversationId,
        @NotBlank @Size(max = 1000) String message,
        @Size(max = 320) String contextPath
) {
}

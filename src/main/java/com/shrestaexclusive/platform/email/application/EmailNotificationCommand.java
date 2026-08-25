package com.shrestaexclusive.platform.email.application;

import java.util.Map;
import java.util.UUID;

import com.shrestaexclusive.platform.email.domain.NotificationType;

public record EmailNotificationCommand(
        NotificationType type,
        String recipient,
        Map<String, String> variables,
        String idempotencyKey,
        UUID customerId,
        String correlationId
) {
    public EmailNotificationCommand {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
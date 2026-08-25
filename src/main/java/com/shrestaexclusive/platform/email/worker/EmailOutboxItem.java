package com.shrestaexclusive.platform.email.worker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.shrestaexclusive.platform.email.domain.NotificationType;

public record EmailOutboxItem(UUID id, NotificationType type, String recipient, Map<String, String> variables,
        String idempotencyKey, int attemptCount, Instant expiresAt, Instant createdAt, String leaseOwner,
        long leaseGeneration) {}
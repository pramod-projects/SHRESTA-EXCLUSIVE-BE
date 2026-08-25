package com.shrestaexclusive.platform.email.domain;

import java.util.UUID;

public record EmailMessage(UUID notificationId, String idempotencyKey, String recipient, String senderEmail,
        String senderName, String subject, String htmlBody, String textBody) {}
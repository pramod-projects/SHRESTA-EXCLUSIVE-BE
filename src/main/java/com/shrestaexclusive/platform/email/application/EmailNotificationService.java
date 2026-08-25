package com.shrestaexclusive.platform.email.application;

import java.util.Optional;
import java.util.UUID;

public interface EmailNotificationService {
    Optional<UUID> enqueue(EmailNotificationCommand command);
}
package com.shrestaexclusive.platform.email.provider.capture;

import org.springframework.stereotype.Component;

import com.shrestaexclusive.platform.email.domain.EmailMessage;
import com.shrestaexclusive.platform.email.domain.EmailProvider;
import com.shrestaexclusive.platform.email.domain.EmailProviderResult;

@Component
public class CaptureEmailProvider implements EmailProvider {
    @Override public String code() { return "CAPTURE"; }
    @Override public boolean enabled() { return true; }
    @Override public boolean configured() { return true; }
    @Override public boolean webhookConfigured() { return true; }
    @Override public EmailProviderResult send(EmailMessage message) {
        return EmailProviderResult.accepted("capture-" + message.notificationId(), 202, 0);
    }
}
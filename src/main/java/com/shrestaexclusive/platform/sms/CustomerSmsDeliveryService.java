package com.shrestaexclusive.platform.sms;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.shrestaexclusive.platform.sms.provider.SmsProviderClient;
import com.shrestaexclusive.platform.sms.provider.SmsProviderResult;
import com.shrestaexclusive.platform.sms.provider.msg91.Msg91SmsProviderClient;
import com.shrestaexclusive.platform.sms.provider.springedge.SpringEdgeSmsProviderClient;

@Service
public class CustomerSmsDeliveryService {

    private final JdbcClient jdbcClient;
    private final CustomerSmsProperties properties;
    private final List<SmsProviderClient> providerClients;
    private final Clock clock;

    @Autowired
    public CustomerSmsDeliveryService(
            JdbcClient jdbcClient,
            CustomerSmsProperties properties,
            SpringEdgeSmsProviderClient springEdgeClient,
            Msg91SmsProviderClient msg91Client
    ) {
        this(jdbcClient, properties, List.of(springEdgeClient, msg91Client), Clock.systemUTC());
    }

    CustomerSmsDeliveryService(
            JdbcClient jdbcClient,
            CustomerSmsProperties properties,
            List<SmsProviderClient> providerClients,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.providerClients = providerClients;
        this.clock = clock;
    }

    public void sendRegistrationOtp(UUID customerId, String mobile, String otp) {
        String normalizedMobile = normalizeMobile(mobile);
        String messageBody = "Your SHRESTA verification OTP is %s. Do not share this code with anyone.".formatted(otp);
        sendCustomerSms(customerId, normalizedMobile, "REGISTRATION_OTP", messageBody);
    }

    public void sendCustomerSms(UUID customerId, String mobile, String purpose, String messageBody) {
        UUID smsMessageId = createSmsMessage(customerId, mobile, purpose, messageBody);

        if (!properties.isDeliveryEnabled()) {
            markSkipped(smsMessageId, "SMS delivery is disabled by configuration.");
            return;
        }

        String failure = null;
        for (SmsProviderClient providerClient : providerClients) {
            SmsProviderResult attempt = providerClient.send(mobile, messageBody);
            recordAttempt(
                    customerId,
                    smsMessageId,
                    providerClient.providerCode(),
                    attempt.success() ? "SUCCESS" : "FAILED",
                    attempt.providerMessageId(),
                    attempt.httpStatus(),
                    attempt.requestPayload(),
                    attempt.responsePayload(),
                    attempt.errorMessage()
            );

            if (attempt.success()) {
                markSent(smsMessageId, providerClient.providerCode());
                return;
            }

            if (attempt.errorMessage() != null && !attempt.errorMessage().isBlank()) {
                failure = attempt.errorMessage();
            }
        }
        markFailed(smsMessageId, failure == null ? "SMS delivery failed for both providers." : failure);
    }

    private UUID createSmsMessage(UUID customerId, String mobile, String purpose, String messageBody) {
        return jdbcClient.sql("""
                        INSERT INTO customer_sms_messages (
                            customer_id,
                            mobile_number,
                            purpose,
                            message_body,
                            status,
                            provider_priority
                        )
                        VALUES (
                            :customerId,
                            :mobileNumber,
                            :purpose,
                            :messageBody,
                            'PENDING',
                            'SPRINGEDGE,MSG91'
                        )
                        RETURNING id
                        """)
                .param("customerId", customerId)
                .param("mobileNumber", mobile)
                .param("purpose", purpose)
                .param("messageBody", messageBody)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();
    }

    private void recordAttempt(
            UUID customerId,
            UUID smsMessageId,
            String provider,
            String status,
            String providerMessageId,
            Integer httpStatus,
            String requestPayload,
            String responsePayload,
            String errorMessage
    ) {
        jdbcClient.sql("""
                        INSERT INTO customer_sms_attempts (
                            sms_message_id,
                            customer_id,
                            provider,
                            status,
                            provider_message_id,
                            http_status,
                            request_payload,
                            response_payload,
                            error_message,
                            attempted_at
                        )
                        VALUES (
                            :smsMessageId,
                            :customerId,
                            :provider,
                            :status,
                            :providerMessageId,
                            :httpStatus,
                            :requestPayload,
                            :responsePayload,
                            :errorMessage,
                            :attemptedAt
                        )
                        """)
                .param("smsMessageId", smsMessageId)
                .param("customerId", customerId)
                .param("provider", provider)
                .param("status", status)
                .param("providerMessageId", providerMessageId)
                .param("httpStatus", httpStatus)
                .param("requestPayload", truncate(requestPayload, 4000))
                .param("responsePayload", truncate(responsePayload, 4000))
                .param("errorMessage", truncate(errorMessage, 500))
                .param("attemptedAt", Timestamp.from(Instant.now(clock)))
                .update();
    }

    private void markSent(UUID smsMessageId, String provider) {
        jdbcClient.sql("""
                        UPDATE customer_sms_messages
                        SET status = 'SENT',
                            provider_used = :provider,
                            sent_at = :sentAt,
                            failure_reason = NULL,
                            updated_at = :sentAt
                        WHERE id = :id
                        """)
                .param("provider", provider)
                .param("sentAt", Timestamp.from(Instant.now(clock)))
                .param("id", smsMessageId)
                .update();
    }

    private void markFailed(UUID smsMessageId, String reason) {
        jdbcClient.sql("""
                        UPDATE customer_sms_messages
                        SET status = 'FAILED',
                            failure_reason = :failureReason,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("failureReason", truncate(reason, 255))
                .param("updatedAt", Timestamp.from(Instant.now(clock)))
                .param("id", smsMessageId)
                .update();
    }

    private void markSkipped(UUID smsMessageId, String reason) {
        jdbcClient.sql("""
                        UPDATE customer_sms_messages
                        SET status = 'SKIPPED',
                            failure_reason = :failureReason,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("failureReason", truncate(reason, 255))
                .param("updatedAt", Timestamp.from(Instant.now(clock)))
                .param("id", smsMessageId)
                .update();
    }

    private static String normalizeMobile(String mobile) {
        String digits = mobile.trim().replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

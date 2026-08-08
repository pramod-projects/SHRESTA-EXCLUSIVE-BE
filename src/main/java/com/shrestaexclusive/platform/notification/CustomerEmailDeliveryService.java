package com.shrestaexclusive.platform.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class CustomerEmailDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerEmailDeliveryService.class);

    private final JavaMailSender mailSender;
    private final CustomerEmailProperties properties;
    private final MailProperties mailProperties;

    public CustomerEmailDeliveryService(
            JavaMailSender mailSender,
            CustomerEmailProperties properties,
            MailProperties mailProperties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.mailProperties = mailProperties;
    }

    public void sendRegistrationOtp(UUID customerId, String email, String otp) {
        String body = "Your SHRESTA verification OTP is %s. Do not share this code with anyone.".formatted(otp);
        sendCustomerEmail(customerId, email, "REGISTRATION_OTP", properties.getRegistrationOtpSubject(), body);
    }

    public void sendInvoiceNotification(UUID customerId, String email, String orderNumber, String invoiceBody) {
        String subject = properties.getInvoiceSubjectPrefix() + " - " + orderNumber;
        sendCustomerEmail(customerId, email, "INVOICE", subject, invoiceBody);
    }

    public void sendCustomerEmail(UUID customerId, String email, String purpose, String subject, String body) {
        if (!properties.isEnabled() && !isMailTransportConfigured()) {
            logger.info("Email delivery disabled, skipping purpose={} customerId={}", purpose, customerId);
            return;
        }
        if (!StringUtils.hasText(properties.getFromAddress())) {
            logger.warn("Email delivery skipped because from-address is not configured, purpose={} customerId={}", purpose, customerId);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.getFromAddress());
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException | RuntimeException exception) {
            throw new IllegalStateException("Email delivery failed for purpose " + purpose + ": " + exception.getMessage(), exception);
        }
    }

    private boolean isMailTransportConfigured() {
        return StringUtils.hasText(mailProperties.getHost())
                && StringUtils.hasText(mailProperties.getUsername())
                && StringUtils.hasText(mailProperties.getPassword());
    }
}

package com.shrestaexclusive.platform.email.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.NotificationType;

@Component
public class EmailTemplateRenderer {
    private static final Map<NotificationType, String> SUBJECTS = new EnumMap<>(NotificationType.class);
    private static final Map<NotificationType, String> PREHEADERS = new EnumMap<>(NotificationType.class);
    static {
        SUBJECTS.put(NotificationType.OTP, "Your SHRESTA verification code");
        SUBJECTS.put(NotificationType.EMAIL_VERIFICATION, "Verify your SHRESTA email");
        SUBJECTS.put(NotificationType.PASSWORD_RESET, "Reset your SHRESTA password");
        SUBJECTS.put(NotificationType.ACCOUNT_SECURITY, "SHRESTA account security alert");
        SUBJECTS.put(NotificationType.ORDER_CONFIRMATION, "Your SHRESTA order is confirmed");
        SUBJECTS.put(NotificationType.ORDER_CANCELLED, "Your SHRESTA order was cancelled");
        SUBJECTS.put(NotificationType.ORDER_SHIPPED, "Your SHRESTA order has shipped");
        SUBJECTS.put(NotificationType.ORDER_OUT_FOR_DELIVERY, "Your SHRESTA order is out for delivery");
        SUBJECTS.put(NotificationType.ORDER_DELIVERED, "Your SHRESTA order was delivered");
        SUBJECTS.put(NotificationType.PAYMENT_SUCCESS, "SHRESTA payment received");
        SUBJECTS.put(NotificationType.PAYMENT_FAILED, "SHRESTA payment failed");
        SUBJECTS.put(NotificationType.REFUND_INITIATED, "Your SHRESTA refund has started");
        SUBJECTS.put(NotificationType.REFUND_COMPLETED, "Your SHRESTA refund is complete");

        PREHEADERS.put(NotificationType.OTP, "Use this one-time code to continue securely.");
        PREHEADERS.put(NotificationType.EMAIL_VERIFICATION, "Confirm your email address for your SHRESTA account.");
        PREHEADERS.put(NotificationType.PASSWORD_RESET, "Use this secure link to reset your SHRESTA password.");
        PREHEADERS.put(NotificationType.ACCOUNT_SECURITY, "Important information about your SHRESTA account.");
        PREHEADERS.put(NotificationType.ORDER_CONFIRMATION, "We have received and confirmed your order.");
        PREHEADERS.put(NotificationType.ORDER_CANCELLED, "Your order cancellation has been recorded.");
        PREHEADERS.put(NotificationType.ORDER_SHIPPED, "Your SHRESTA order is on its way.");
        PREHEADERS.put(NotificationType.ORDER_OUT_FOR_DELIVERY, "Your order is scheduled to arrive soon.");
        PREHEADERS.put(NotificationType.ORDER_DELIVERED, "Your SHRESTA order has been delivered.");
        PREHEADERS.put(NotificationType.PAYMENT_SUCCESS, "Your payment was received successfully.");
        PREHEADERS.put(NotificationType.PAYMENT_FAILED, "Your payment could not be completed.");
        PREHEADERS.put(NotificationType.REFUND_INITIATED, "Your refund request is being processed.");
        PREHEADERS.put(NotificationType.REFUND_COMPLETED, "Your refund has been completed.");
    }

    private final SpringTemplateEngine htmlEngine;
    private final EmailProperties properties;

    public EmailTemplateRenderer(EmailProperties properties) {
        this.properties = properties;
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(true);
        htmlEngine = new SpringTemplateEngine();
        htmlEngine.setTemplateResolver(resolver);
    }

    public RenderedEmail render(NotificationType type, Map<String, String> variables) {
        if (!variables.keySet().equals(type.requiredVariables())) {
            throw new IllegalArgumentException("Template variables do not match " + type);
        }
        Context context = new Context();
        variables.forEach(context::setVariable);
        String content = htmlEngine.process(resource(type, "html"), context);
        String html = resource("layout.html")
            .replace("{{subject}}", SUBJECTS.get(type))
            .replace("{{preheader}}", PREHEADERS.get(type))
            .replace("{{supportEmail}}", HtmlUtils.htmlEscape(validSupportEmail()))
            .replace("{{supportPhoneDisplay}}", HtmlUtils.htmlEscape(validSupportPhoneDisplay()))
            .replace("{{supportPhoneDial}}", validSupportPhoneDial())
            .replace("{{content}}", content);
        String text = resource(type, "txt");
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            text = text.replace("{{" + variable.getKey() + "}}", variable.getValue());
        }
        if (text.contains("{{")) throw new IllegalStateException("Unresolved template variable for " + type);
        return new RenderedEmail(SUBJECTS.get(type), html, text);
    }

    private String validSupportEmail() {
        String value = properties.getSupportEmail() == null ? "" : properties.getSupportEmail().trim();
        if (!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || containsControlCharacter(value)) {
            throw new IllegalStateException("Invalid support email configuration");
        }
        return value;
    }

    private String validSupportPhoneDisplay() {
        String value = properties.getSupportPhoneDisplay() == null ? "" : properties.getSupportPhoneDisplay().trim();
        if (!value.matches("^[+0-9 ()-]{7,30}$") || containsControlCharacter(value)) {
            throw new IllegalStateException("Invalid support phone display configuration");
        }
        return value;
    }

    private String validSupportPhoneDial() {
        String value = properties.getSupportPhoneDial() == null ? "" : properties.getSupportPhoneDial().trim();
        if (!value.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalStateException("Invalid support phone dial configuration");
        }
        return value;
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private String resource(NotificationType type, String extension) {
        return resource(type.name().toLowerCase() + "." + extension);
    }

    private String resource(String name) {
        try {
            return new ClassPathResource("email/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing email template " + name, exception);
        }
    }
}
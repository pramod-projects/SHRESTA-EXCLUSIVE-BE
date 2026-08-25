package com.shrestaexclusive.platform.email.template;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.shrestaexclusive.platform.email.configuration.EmailProperties;
import com.shrestaexclusive.platform.email.domain.NotificationType;

class EmailTemplateRendererTest {
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer(properties());

    @Test void rendersEveryApplicationOwnedTemplateAndEscapesHtml() {
        for (NotificationType type : NotificationType.values()) {
            Map<String, String> variables = new HashMap<>();
            type.requiredVariables().forEach(key -> variables.put(key, key.endsWith("Url") ? "https://example.com/action" : "<unsafe>"));
            RenderedEmail rendered = renderer.render(type, variables);
            assertThat(rendered.subject()).isNotBlank();
            assertThat(rendered.html())
                    .doesNotContain("<unsafe>")
                    .contains("&lt;unsafe&gt;")
                    .contains("<!doctype html>", "role=\"presentation\"", "SHRESTA")
                    .contains("mailto:care@example.com", "care@example.com")
                    .contains("tel:+919876543210", "+91 98765 43210")
                    .contains("display:none", "@media only screen and (max-width: 620px)")
                    .contains("aria-label=\"SHRESTA Exclusive\"");
            assertThat(rendered.text()).doesNotContain("{{");
        }
    }

    @Test void rejectsIncompleteVariables() {
        assertThatThrownBy(() -> renderer.render(NotificationType.OTP, Map.of("otp", "123456")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EmailProperties properties() {
        EmailProperties properties = new EmailProperties();
        properties.setSupportEmail("care@example.com");
        properties.setSupportPhoneDisplay("+91 98765 43210");
        properties.setSupportPhoneDial("+919876543210");
        return properties;
    }
}
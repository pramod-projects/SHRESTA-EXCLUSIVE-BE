package com.shrestaexclusive.platform.payment.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class RazorpayCheckoutServiceTest {

    @Test
    void verifyPaymentReturnsVerifiedWhenSignatureMatches() {
        RazorpayCheckoutProperties properties = configuredProperties();
        RazorpayCheckoutService service = new RazorpayCheckoutService(properties, new ObjectMapper());

        String orderId = "order_9A33XWu170gUtm";
        String paymentId = "pay_29QQoUBi66xm2f";
        String signature = hmacSha256Hex(properties.getKeySecret(), orderId + "|" + paymentId);

        RazorpayVerifyPaymentResponse response = service.verifyPayment(
                new RazorpayVerifyPaymentRequest(orderId, paymentId, signature)
        );

        assertThat(response.verified()).isTrue();
        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.paymentId()).isEqualTo(paymentId);
    }

    @Test
    void verifyPaymentThrowsWhenSignatureDoesNotMatch() {
        RazorpayCheckoutProperties properties = configuredProperties();
        RazorpayCheckoutService service = new RazorpayCheckoutService(properties, new ObjectMapper());

        assertThatThrownBy(() -> service.verifyPayment(new RazorpayVerifyPaymentRequest(
                "order_9A33XWu170gUtm",
                "pay_29QQoUBi66xm2f",
                "invalid_signature"
        )))
                .isInstanceOf(RazorpayCheckoutSignatureMismatchException.class)
                .hasMessageContaining("signature mismatch");
    }

    private static RazorpayCheckoutProperties configuredProperties() {
        RazorpayCheckoutProperties properties = new RazorpayCheckoutProperties();
        properties.setEnabled(true);
        properties.setKeyId("rzp_test_key_id");
        properties.setKeySecret("test_secret");
        return properties;
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not compute signature for test.", exception);
        }
    }
}

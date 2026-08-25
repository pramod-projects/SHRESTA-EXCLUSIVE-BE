package com.shrestaexclusive.platform.payment.razorpay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class RazorpayCheckoutService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final RazorpayCheckoutProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public RazorpayCheckoutService(RazorpayCheckoutProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    RazorpayCheckoutService(RazorpayCheckoutProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public RazorpayCreateOrderResponse createOrder(RazorpayCreateOrderRequest request) {
        ensureConfigured();

        String payload = "{" +
                "\"amount\":" + request.amount() + "," +
                "\"currency\":\"" + escapeJson(request.currency().trim().toUpperCase(Locale.ROOT)) + "\"," +
                "\"receipt\":\"" + escapeJson(request.receipt().trim()) + "\"" +
                "}";

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getOrderEndpoint()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status == 401 || status == 403) {
                throw new RazorpayCheckoutAuthException("Razorpay authentication failed while creating order.");
            }
            if (status < 200 || status >= 300) {
                throw new RazorpayCheckoutUpstreamException("Razorpay order creation failed with HTTP " + status + ".");
            }

            JsonNode root = objectMapper.readTree(body);
            String orderId = text(root, "id");
            Long amount = longValue(root, "amount");
            String currency = text(root, "currency");

            if (!StringUtils.hasText(orderId) || amount == null || !StringUtils.hasText(currency)) {
                throw new RazorpayCheckoutUpstreamException("Razorpay response missing required order fields.");
            }

            return new RazorpayCreateOrderResponse(orderId, amount, currency.toUpperCase(Locale.ROOT));
        } catch (IOException exception) {
            throw new RazorpayCheckoutUpstreamException("Unable to parse Razorpay create-order response.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RazorpayCheckoutUpstreamException("Razorpay request interrupted while creating order.");
        }
    }

    public RazorpayVerifyPaymentResponse verifyPayment(RazorpayVerifyPaymentRequest request) {
        ensureConfigured();

        String orderId = request.razorpayOrderId().trim();
        String paymentId = request.razorpayPaymentId().trim();
        String providedSignature = request.razorpaySignature().trim().toLowerCase(Locale.ROOT);

        String signedPayload = orderId + "|" + paymentId;
        String expectedSignature = hmacSha256Hex(properties.getKeySecret(), signedPayload);
        if (!secureEquals(expectedSignature, providedSignature)) {
            throw new RazorpayCheckoutSignatureMismatchException("Razorpay payment signature mismatch.");
        }

        return new RazorpayVerifyPaymentResponse(true, orderId, paymentId);
    }

    public String fetchPaymentMethod(String paymentId) {
        ensureConfigured();

        String normalizedPaymentId = paymentId == null ? "" : paymentId.trim();
        if (!StringUtils.hasText(normalizedPaymentId)) {
            throw new RazorpayCheckoutUpstreamException("Razorpay payment id is required to fetch payment method.");
        }

        String endpoint = "https://api.razorpay.com/v1/payments/" + normalizedPaymentId;
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Accept", "application/json")
                .header("Authorization", basicAuthHeader())
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status == 401 || status == 403) {
                throw new RazorpayCheckoutAuthException("Razorpay authentication failed while fetching payment method.");
            }
            if (status == 404) {
                throw new RazorpayCheckoutUpstreamException("Razorpay payment was not found.");
            }
            if (status < 200 || status >= 300) {
                throw new RazorpayCheckoutUpstreamException("Razorpay payment fetch failed with HTTP " + status + ".");
            }

            JsonNode root = objectMapper.readTree(body);
            String providerMethod = text(root, "method");
            if (!StringUtils.hasText(providerMethod)) {
                return null;
            }

            return normalizeOrderPaymentMethod(providerMethod);
        } catch (IOException exception) {
            throw new RazorpayCheckoutUpstreamException("Unable to parse Razorpay payment response.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RazorpayCheckoutUpstreamException("Razorpay request interrupted while fetching payment method.");
        }
    }

    public RazorpayCreateRefundResponse createRefund(String paymentId, long amountPaise, String note) {
        ensureConfigured();

        String normalizedPaymentId = paymentId == null ? "" : paymentId.trim();
        if (!StringUtils.hasText(normalizedPaymentId)) {
            throw new RazorpayCheckoutUpstreamException("Razorpay payment id is required for refund.");
        }
        if (amountPaise < 1) {
            throw new RazorpayCheckoutUpstreamException("Refund amount must be at least 1 paise.");
        }

        String payload;
        String normalizedNote = note == null ? "" : note.trim();
        if (StringUtils.hasText(normalizedNote)) {
            payload = "{" +
                    "\"amount\":" + amountPaise + "," +
                    "\"notes\":{\"reason\":\"" + escapeJson(normalizedNote) + "\"}" +
                    "}";
        } else {
            payload = "{" +
                    "\"amount\":" + amountPaise +
                    "}";
        }

        String endpoint = "https://api.razorpay.com/v1/payments/" + normalizedPaymentId + "/refund";
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status == 401 || status == 403) {
                throw new RazorpayCheckoutAuthException("Razorpay authentication failed while creating refund.");
            }
            if (status < 200 || status >= 300) {
                throw new RazorpayCheckoutUpstreamException("Razorpay refund request failed with HTTP " + status + ".");
            }

            JsonNode root = objectMapper.readTree(body);
            String refundId = text(root, "id");
            String responsePaymentId = text(root, "payment_id");
            String refundStatus = text(root, "status");
            Long amount = longValue(root, "amount");
            String currency = text(root, "currency");

            if (!StringUtils.hasText(refundId)
                    || !StringUtils.hasText(responsePaymentId)
                    || !StringUtils.hasText(refundStatus)) {
                throw new RazorpayCheckoutUpstreamException("Razorpay response missing required refund fields.");
            }

            return new RazorpayCreateRefundResponse(
                    refundId,
                    responsePaymentId,
                    refundStatus.toUpperCase(Locale.ROOT),
                    amount,
                    currency == null ? null : currency.toUpperCase(Locale.ROOT)
            );
        } catch (IOException exception) {
            throw new RazorpayCheckoutUpstreamException("Unable to parse Razorpay refund response.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RazorpayCheckoutUpstreamException("Razorpay request interrupted while creating refund.");
        }
    }

    public RazorpayRefundStatusResponse fetchRefundStatus(String refundId) {
        ensureConfigured();

        String normalizedRefundId = refundId == null ? "" : refundId.trim();
        if (!StringUtils.hasText(normalizedRefundId)) {
            throw new RazorpayCheckoutUpstreamException("Razorpay refund id is required.");
        }

        String endpoint = "https://api.razorpay.com/v1/refunds/" + normalizedRefundId;
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Accept", "application/json")
                .header("Authorization", basicAuthHeader())
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status == 401 || status == 403) {
                throw new RazorpayCheckoutAuthException("Razorpay authentication failed while checking refund status.");
            }
            if (status == 404) {
                throw new RazorpayCheckoutUpstreamException("Razorpay refund was not found.");
            }
            if (status < 200 || status >= 300) {
                throw new RazorpayCheckoutUpstreamException("Razorpay refund status check failed with HTTP " + status + ".");
            }

            JsonNode root = objectMapper.readTree(body);
            String responseRefundId = text(root, "id");
            String responsePaymentId = text(root, "payment_id");
            String refundStatus = text(root, "status");
            Long amount = longValue(root, "amount");
            String currency = text(root, "currency");

            if (!StringUtils.hasText(responseRefundId) || !StringUtils.hasText(refundStatus)) {
                throw new RazorpayCheckoutUpstreamException("Razorpay response missing required refund status fields.");
            }

            return new RazorpayRefundStatusResponse(
                    responseRefundId,
                    responsePaymentId,
                    refundStatus.toUpperCase(Locale.ROOT),
                    amount,
                    currency == null ? null : currency.toUpperCase(Locale.ROOT)
            );
        } catch (IOException exception) {
            throw new RazorpayCheckoutUpstreamException("Unable to parse Razorpay refund status response.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RazorpayCheckoutUpstreamException("Razorpay request interrupted while checking refund status.");
        }
    }

    private void ensureConfigured() {
        if (!properties.isActive() || !properties.isConfigured()) {
            throw new RazorpayCheckoutConfigurationException("Razorpay checkout credentials are not configured.");
        }
    }

    private String basicAuthHeader() {
        String token = properties.getKeyId().trim() + ":" + properties.getKeySecret().trim();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return StringUtils.hasText(value) ? value : null;
    }

    private static Long longValue(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return node.asLong();
    }

    private static String escapeJson(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte byteValue : digest) {
                builder.append(String.format("%02x", byteValue));
            }
            return builder.toString();
        } catch (GeneralSecurityException exception) {
            throw new RazorpayCheckoutUpstreamException("Could not compute Razorpay payment signature.");
        }
    }

    private static String normalizeOrderPaymentMethod(String providerMethod) {
        String normalized = providerMethod.trim().toUpperCase(Locale.ROOT);
        if ("UPI".equals(normalized)) {
            return "UPI";
        }
        if ("NETBANKING".equals(normalized)) {
            return "NETBANKING";
        }
        return "CARD";
    }

    private static boolean secureEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length(); i += 1) {
            result |= left.charAt(i) ^ right.charAt(i);
        }
        return result == 0;
    }
}

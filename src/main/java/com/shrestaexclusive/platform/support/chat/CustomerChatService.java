package com.shrestaexclusive.platform.support.chat;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerChatService {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    @Autowired
    public CustomerChatService(JdbcClient jdbcClient) {
        this(jdbcClient, Clock.systemUTC());
    }

    CustomerChatService(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Transactional
    public CustomerChatMessageResponse reply(CustomerChatMessageRequest request) {
        UUID conversationId = resolveConversation(request.conversationId(), request.contextPath());
        String customerMessage = request.message().trim();
        ChatReply reply = buildReply(customerMessage);
        Instant now = Instant.now(clock);

        insertMessage(conversationId, "CUSTOMER", customerMessage, now);
        insertMessage(conversationId, "ASSISTANT", reply.message(), now);

        return new CustomerChatMessageResponse(
                conversationId.toString(),
                reply.message(),
                reply.quickActions(),
                reply.escalationSuggested(),
                now
        );
    }

    private UUID resolveConversation(String requestedConversationId, String contextPath) {
        UUID existingId = parseUuid(requestedConversationId);
        if (existingId != null && sessionExists(existingId)) {
            return existingId;
        }

        UUID newId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO customer_chat_sessions (id, channel, status, context_path)
                        VALUES (:id, 'WEB', 'ACTIVE', :contextPath)
                        """)
                .param("id", newId)
                .param("contextPath", contextPath)
                .update();
        return newId;
    }

    private boolean sessionExists(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM customer_chat_sessions
                        WHERE id = :id
                          AND status IN ('ACTIVE', 'ESCALATED')
                        """)
                .param("id", conversationId)
                .query(Integer.class)
                .single() > 0;
    }

    private void insertMessage(UUID conversationId, String sender, String message, Instant createdAt) {
        jdbcClient.sql("""
                        INSERT INTO customer_chat_messages (session_id, sender, message_text, created_at)
                        VALUES (:sessionId, :sender, :messageText, :createdAt)
                        """)
                .param("sessionId", conversationId)
                .param("sender", sender)
                .param("messageText", message)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        jdbcClient.sql("""
                        UPDATE customer_chat_sessions
                        SET updated_at = :updatedAt
                        WHERE id = :sessionId
                        """)
                .param("sessionId", conversationId)
                .param("updatedAt", Timestamp.from(createdAt))
                .update();
    }

    private ChatReply buildReply(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "order", "track", "invoice", "refund")) {
            return new ChatReply(
                    "I can help with orders after secure login. Open Account, sign in, then choose Order support so private order details stay protected.",
                    List.of("Login to account", "Order support", "Continue shopping"),
                    true
            );
        }
        if (containsAny(normalized, "store", "map", "near", "pickup", "appointment")) {
            return new ChatReply(
                    "Use Stores to view SHRESTA hubs on the free OpenStreetMap locator, search your city, and zoom into the selected store.",
                    List.of("Find stores", "Delivery timing", "Book appointment"),
                    false
            );
        }
        if (containsAny(normalized, "saree", "silk", "weave", "product", "price", "occasion")) {
            return new ChatReply(
                "Tell me the occasion and budget, and I will guide you to saree options from the SHRESTA storefront catalog.",
                List.of("Wedding sarees", "Silk sarees", "Festival sarees"),
                    false
            );
        }
        if (containsAny(normalized, "cart", "checkout", "payment", "login", "otp")) {
            return new ChatReply(
                    "You can browse and review the cart without login. Sign in with OTP only when you confirm and pay, and the cart stays preserved.",
                    List.of("View cart", "Login help", "Checkout help"),
                    false
            );
        }

        return new ChatReply(
                "I am SHRESTA Assistant. I can help with product discovery, store pickup, checkout readiness, and order-support handoff.",
                List.of("Product help", "Find stores", "Checkout help"),
                false
        );
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record ChatReply(String message, List<String> quickActions, boolean escalationSuggested) {
    }
}

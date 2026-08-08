package com.shrestaexclusive.platform.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CustomerOrderLifecycleService {

    private static final Set<String> TERMINAL_ORDER_STATUSES = Set.of("DELIVERED", "CANCELLED", "PAYMENT_FAILED");
    private static final Set<String> TERMINAL_FULFILLMENT_STATUSES = Set.of("DELIVERED", "CANCELLED");
    private static final Map<String, Integer> ORDER_STATUS_RANK = Map.of(
            "PLACED", 0,
            "PAYMENT_PENDING", 1,
            "CONFIRMED", 2,
            "PACKING", 3,
            "READY_FOR_PICKUP", 4,
            "OUT_FOR_DELIVERY", 5,
            "DELIVERED", 6,
            "CANCELLED", 6,
            "PAYMENT_FAILED", 6
    );
    private static final Map<String, Integer> FULFILLMENT_STATUS_RANK = Map.of(
            "PENDING", 0,
            "ALLOCATED", 1,
            "PACKING", 2,
            "READY", 3,
            "SHIPPED", 4,
            "DELIVERED", 5,
            "CANCELLED", 5
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CustomerOrderService customerOrderService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CustomerOrderLifecycleService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CustomerOrderService customerOrderService,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, customerOrderService, objectMapper, Clock.systemUTC());
    }

    CustomerOrderLifecycleService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CustomerOrderService customerOrderService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerOrderService = customerOrderService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminOrderSummaryResponse> listOrdersForAdmin(int limit, int offset, String customerEmail, String orderNumber) {
        return jdbcTemplate.query("""
                SELECT order_row.order_number,
                       order_row.customer_id,
                       order_row.customer_email,
                       account.display_name,
                       order_row.status,
                       order_row.payment_status,
                       order_row.fulfillment_status,
                       order_row.delivery_mode,
                       order_row.payment_method,
                       order_row.total_paise,
                       order_row.placed_at,
                       COALESCE(SUM(item.quantity), 0)::int AS item_count
                FROM customer_orders order_row
                JOIN customer_accounts account ON account.id = order_row.customer_id
                LEFT JOIN customer_order_items item ON item.order_id = order_row.id
                WHERE (:customerEmail = '' OR order_row.customer_email = :customerEmail)
                  AND (:orderNumber = '' OR order_row.order_number = :orderNumber)
                GROUP BY order_row.id, order_row.order_number, order_row.customer_id, order_row.customer_email,
                         account.display_name, order_row.status, order_row.payment_status, order_row.fulfillment_status,
                         order_row.delivery_mode, order_row.payment_method, order_row.total_paise, order_row.placed_at
                ORDER BY order_row.placed_at DESC
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("customerEmail", normalizedFilter(customerEmail))
                .addValue("orderNumber", normalizedFilter(orderNumber))
                .addValue("limit", Math.max(1, Math.min(limit, 200)))
                .addValue("offset", Math.max(offset, 0)), (rs, rowNum) -> new AdminOrderSummaryResponse(
                rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class).toString(),
                rs.getString("customer_email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status"),
                rs.getString("delivery_mode"),
                rs.getString("payment_method"),
                rs.getLong("total_paise"),
                rs.getInt("item_count"),
                rs.getTimestamp("placed_at").toInstant()
        ));
    }

    @Transactional(readOnly = true)
    public List<AdminCustomerOrderSummaryResponse> listCustomerSummariesForAdmin(int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT order_row.customer_id,
                       order_row.customer_email,
                       account.display_name,
                       COUNT(*)::int AS total_orders,
                       COUNT(*) FILTER (WHERE order_row.status = 'DELIVERED')::int AS delivered_orders,
                       COUNT(*) FILTER (WHERE order_row.status = 'CANCELLED')::int AS cancelled_orders,
                       COUNT(*) FILTER (WHERE order_row.status NOT IN ('DELIVERED', 'CANCELLED', 'PAYMENT_FAILED'))::int AS active_orders,
                       COALESCE(SUM(order_row.total_paise), 0) AS gross_order_value_paise,
                       MAX(order_row.placed_at) AS last_order_at
                FROM customer_orders order_row
                JOIN customer_accounts account ON account.id = order_row.customer_id
                GROUP BY order_row.customer_id, order_row.customer_email, account.display_name
                ORDER BY last_order_at DESC NULLS LAST
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("limit", Math.max(1, Math.min(limit, 200)))
                .addValue("offset", Math.max(offset, 0)), (rs, rowNum) -> new AdminCustomerOrderSummaryResponse(
                rs.getObject("customer_id", UUID.class).toString(),
                rs.getString("customer_email"),
                rs.getString("display_name"),
                rs.getInt("total_orders"),
                rs.getInt("delivered_orders"),
                rs.getInt("cancelled_orders"),
                rs.getInt("active_orders"),
                rs.getLong("gross_order_value_paise"),
                rs.getTimestamp("last_order_at") == null ? null : rs.getTimestamp("last_order_at").toInstant()
        ));
    }

    @Transactional(readOnly = true)
    public CustomerOrderResponse findOrderForAdmin(String orderNumber) {
        OrderIdentity identity = findOrderIdentity(orderNumber);
        return customerOrderService.findOrderForCustomer(identity.customerId(), identity.orderNumber());
    }

    @Transactional
    public CustomerOrderResponse cancelOrderByCustomer(UUID customerId, String orderNumber, String note) {
        MutableOrderRow order = findMutableOrderForCustomer(customerId, orderNumber);
        String normalizedNote = optionalTrim(note);

        if ("CANCELLED".equals(order.orderStatus()) && "CANCELLED".equals(order.fulfillmentStatus())) {
            return customerOrderService.findOrderForCustomer(customerId, orderNumber);
        }
        if ("DELIVERED".equals(order.orderStatus()) || "DELIVERED".equals(order.fulfillmentStatus())) {
            throw new CustomerOrderPlacementException("Delivered orders cannot be cancelled.");
        }

        String nextPaymentStatus = order.paymentStatus();
        if ("CAPTURED".equals(order.paymentStatus())) {
            nextPaymentStatus = "REFUNDED";
        }

        Instant now = Instant.now(clock);
        updateOrderStatuses(order.id(), "CANCELLED", nextPaymentStatus, "CANCELLED", now);
        insertStatusEvent(order.id(), "ORDER_STATUS", order.orderStatus(), "CANCELLED", "CUSTOMER", customerId.toString(),
                normalizedNote == null ? "Customer cancelled this order." : normalizedNote, now);
        if (!order.paymentStatus().equals(nextPaymentStatus)) {
            insertStatusEvent(order.id(), "PAYMENT_STATUS", order.paymentStatus(), nextPaymentStatus, "SYSTEM", null,
                    "Payment refunded after customer cancellation.", now);
        }
        if (!"CANCELLED".equals(order.fulfillmentStatus())) {
            insertStatusEvent(order.id(), "FULFILLMENT_STATUS", order.fulfillmentStatus(), "CANCELLED", "SYSTEM", null,
                    "Fulfillment cancelled after customer request.", now);
        }

        return customerOrderService.findOrderForCustomer(customerId, orderNumber);
    }

    @Transactional
    public CustomerOrderResponse updateOrderStatusesByAdmin(String orderNumber, AdminOrderStatusUpdateRequest request, String actorId) {
        MutableOrderRow order = findMutableOrder(orderNumber);

        String targetOrderStatus = normalizedOrCurrent(request.orderStatus(), order.orderStatus());
        String targetPaymentStatus = normalizedOrCurrent(request.paymentStatus(), order.paymentStatus());
        String targetFulfillmentStatus = normalizedOrCurrent(request.fulfillmentStatus(), order.fulfillmentStatus());

        if (request.orderStatus() == null && request.paymentStatus() == null && request.fulfillmentStatus() == null) {
            throw new CustomerOrderPlacementException("At least one status update is required.");
        }

        if ("CANCELLED".equals(targetOrderStatus) && "DELIVERED".equals(order.fulfillmentStatus())) {
            throw new CustomerOrderPlacementException("Delivered orders cannot be cancelled.");
        }

        if ("CANCELLED".equals(targetOrderStatus) && request.fulfillmentStatus() == null) {
            targetFulfillmentStatus = "CANCELLED";
        }

        if (isProgressingIntoPaymentRequiredStage(order.orderStatus(), order.fulfillmentStatus(), targetOrderStatus, targetFulfillmentStatus)
            && !"CAPTURED".equals(order.paymentStatus())) {
            throw new CustomerOrderPlacementException("Capture payment before moving the order to packing or later fulfillment stages.");
        }

        validateOrderStatusTransition(order.orderStatus(), targetOrderStatus, targetFulfillmentStatus);
        validatePaymentStatusTransition(order.paymentStatus(), targetPaymentStatus);
        validateFulfillmentStatusTransition(order.fulfillmentStatus(), targetFulfillmentStatus, targetOrderStatus);

        if (order.orderStatus().equals(targetOrderStatus)
                && order.paymentStatus().equals(targetPaymentStatus)
                && order.fulfillmentStatus().equals(targetFulfillmentStatus)) {
            return customerOrderService.findOrderForCustomer(order.customerId(), order.orderNumber());
        }

        Instant now = Instant.now(clock);
        updateOrderStatuses(order.id(), targetOrderStatus, targetPaymentStatus, targetFulfillmentStatus, now);

        String note = optionalTrim(request.note());
        if (!order.orderStatus().equals(targetOrderStatus)) {
            insertStatusEvent(order.id(), "ORDER_STATUS", order.orderStatus(), targetOrderStatus, "ADMIN", actorId,
                    note == null ? "Admin updated order status." : note, now);
        }
        if (!order.paymentStatus().equals(targetPaymentStatus)) {
            insertStatusEvent(order.id(), "PAYMENT_STATUS", order.paymentStatus(), targetPaymentStatus, "ADMIN", actorId,
                    note == null ? "Admin updated payment status." : note, now);
        }
        if (!order.fulfillmentStatus().equals(targetFulfillmentStatus)) {
            insertStatusEvent(order.id(), "FULFILLMENT_STATUS", order.fulfillmentStatus(), targetFulfillmentStatus, "ADMIN", actorId,
                    note == null ? "Admin updated fulfillment status." : note, now);
        }

        return customerOrderService.findOrderForCustomer(order.customerId(), order.orderNumber());
    }

    @Transactional
    public PaymentWebhookOrderUpdateResult applyRazorpayPaymentWebhook(
            String orderNumber,
            String paymentEventType,
            String paymentId,
            String webhookEventId
    ) {
        MutableOrderRow order = findMutableOrder(orderNumber);
        String normalizedEvent = optionalTrim(paymentEventType) == null
                ? ""
                : paymentEventType.trim().toLowerCase(Locale.ROOT);

        String targetPaymentStatus = switch (normalizedEvent) {
            case "payment.authorized" -> maybePromotePayment(order.paymentStatus(), "AUTHORIZED");
            case "payment.captured" -> maybePromotePayment(order.paymentStatus(), "CAPTURED");
            case "payment.failed" -> maybePromotePayment(order.paymentStatus(), "FAILED");
            case "payment.refunded" -> maybePromotePayment(order.paymentStatus(), "REFUNDED");
            default -> order.paymentStatus();
        };

        String targetOrderStatus = order.orderStatus();
        String targetFulfillmentStatus = order.fulfillmentStatus();

        if ("payment.captured".equals(normalizedEvent)
                && "CAPTURED".equals(targetPaymentStatus)
                && "PAYMENT_PENDING".equals(order.orderStatus())) {
            targetOrderStatus = "CONFIRMED";
        }

        if ("payment.failed".equals(normalizedEvent)
                && "FAILED".equals(targetPaymentStatus)
                && !"DELIVERED".equals(order.orderStatus())
                && !"CANCELLED".equals(order.orderStatus())) {
            targetOrderStatus = "PAYMENT_FAILED";
            if (!"DELIVERED".equals(order.fulfillmentStatus())) {
                targetFulfillmentStatus = "CANCELLED";
            }
        }

        if ("payment.refunded".equals(normalizedEvent)
                && "REFUNDED".equals(targetPaymentStatus)
                && !"DELIVERED".equals(order.orderStatus())
                && !"CANCELLED".equals(order.orderStatus())) {
            targetOrderStatus = "CANCELLED";
            if (!"DELIVERED".equals(order.fulfillmentStatus())) {
                targetFulfillmentStatus = "CANCELLED";
            }
        }

        if (order.orderStatus().equals(targetOrderStatus)
                && order.paymentStatus().equals(targetPaymentStatus)
                && order.fulfillmentStatus().equals(targetFulfillmentStatus)) {
            return new PaymentWebhookOrderUpdateResult(
                    false,
                    order.orderNumber(),
                    order.orderStatus(),
                    order.paymentStatus(),
                    order.fulfillmentStatus(),
                    "Webhook event did not change order statuses."
            );
        }

        Instant now = Instant.now(clock);
        updateOrderStatuses(order.id(), targetOrderStatus, targetPaymentStatus, targetFulfillmentStatus, now);

        String noteSuffix = paymentId == null || paymentId.isBlank()
                ? ""
                : " paymentId=" + paymentId;
        String eventSuffix = webhookEventId == null || webhookEventId.isBlank()
                ? ""
                : " eventId=" + webhookEventId;

        if (!order.orderStatus().equals(targetOrderStatus)) {
            insertStatusEvent(order.id(), "ORDER_STATUS", order.orderStatus(), targetOrderStatus, "SYSTEM", "RAZORPAY_WEBHOOK",
                    "Razorpay webhook updated order status." + noteSuffix + eventSuffix, now);
        }
        if (!order.paymentStatus().equals(targetPaymentStatus)) {
            insertStatusEvent(order.id(), "PAYMENT_STATUS", order.paymentStatus(), targetPaymentStatus, "SYSTEM", "RAZORPAY_WEBHOOK",
                    "Razorpay webhook updated payment status." + noteSuffix + eventSuffix, now);
        }
        if (!order.fulfillmentStatus().equals(targetFulfillmentStatus)) {
            insertStatusEvent(order.id(), "FULFILLMENT_STATUS", order.fulfillmentStatus(), targetFulfillmentStatus, "SYSTEM", "RAZORPAY_WEBHOOK",
                    "Razorpay webhook updated fulfillment status." + noteSuffix + eventSuffix, now);
        }

        return new PaymentWebhookOrderUpdateResult(
                true,
                order.orderNumber(),
                targetOrderStatus,
                targetPaymentStatus,
                targetFulfillmentStatus,
                "Webhook event applied."
        );
    }

    private void validateOrderStatusTransition(String current, String target, String targetFulfillmentStatus) {
        if (current.equals(target)) {
            return;
        }
        if (TERMINAL_ORDER_STATUSES.contains(current)) {
            throw new CustomerOrderPlacementException("Order status is terminal and cannot be changed.");
        }
        if ("CANCELLED".equals(target)) {
            return;
        }
        Integer currentRank = ORDER_STATUS_RANK.get(current);
        Integer targetRank = ORDER_STATUS_RANK.get(target);
        if (currentRank == null || targetRank == null || targetRank < currentRank) {
            throw new CustomerOrderPlacementException("Order status cannot move backwards.");
        }
        if ("DELIVERED".equals(target) && !"DELIVERED".equals(targetFulfillmentStatus)) {
            throw new CustomerOrderPlacementException("Set fulfillment status to DELIVERED before closing the order as DELIVERED.");
        }
    }

    private void validatePaymentStatusTransition(String current, String target) {
        if (current.equals(target)) {
            return;
        }

        boolean allowed = switch (current) {
            case "PENDING" -> Set.of("AUTHORIZED", "CAPTURED", "FAILED").contains(target);
            case "AUTHORIZED" -> Set.of("CAPTURED", "FAILED").contains(target);
            case "CAPTURED" -> "REFUNDED".equals(target);
            case "FAILED", "REFUNDED" -> false;
            default -> false;
        };

        if (!allowed) {
            throw new CustomerOrderPlacementException("Invalid payment status transition from " + current + " to " + target + ".");
        }
    }

    private void validateFulfillmentStatusTransition(String current, String target, String targetOrderStatus) {
        if (current.equals(target)) {
            return;
        }
        if (TERMINAL_FULFILLMENT_STATUSES.contains(current)) {
            throw new CustomerOrderPlacementException("Fulfillment status is terminal and cannot be changed.");
        }
        if ("CANCELLED".equals(target) && !"CANCELLED".equals(targetOrderStatus)) {
            throw new CustomerOrderPlacementException("Cancel the order status before cancelling fulfillment.");
        }

        Integer currentRank = FULFILLMENT_STATUS_RANK.get(current);
        Integer targetRank = FULFILLMENT_STATUS_RANK.get(target);
        if (currentRank == null || targetRank == null || targetRank < currentRank) {
            throw new CustomerOrderPlacementException("Fulfillment status cannot move backwards.");
        }
    }

    private boolean requiresCompletedPayment(String targetOrderStatus, String targetFulfillmentStatus) {
        boolean orderRequiresPayment = Set.of("PACKING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED")
            .contains(targetOrderStatus);
        Integer fulfillmentRank = FULFILLMENT_STATUS_RANK.get(targetFulfillmentStatus);
        boolean fulfillmentRequiresPayment = fulfillmentRank != null
            && fulfillmentRank >= FULFILLMENT_STATUS_RANK.get("PACKING")
            && !"CANCELLED".equals(targetFulfillmentStatus);
        return orderRequiresPayment || fulfillmentRequiresPayment;
    }

    private boolean isProgressingIntoPaymentRequiredStage(
            String currentOrderStatus,
            String currentFulfillmentStatus,
            String targetOrderStatus,
            String targetFulfillmentStatus
    ) {
        if (!requiresCompletedPayment(targetOrderStatus, targetFulfillmentStatus)) {
            return false;
        }
        Integer currentOrderRank = ORDER_STATUS_RANK.get(currentOrderStatus);
        Integer targetOrderRank = ORDER_STATUS_RANK.get(targetOrderStatus);
        Integer currentFulfillmentRank = FULFILLMENT_STATUS_RANK.get(currentFulfillmentStatus);
        Integer targetFulfillmentRank = FULFILLMENT_STATUS_RANK.get(targetFulfillmentStatus);

        boolean orderProgressed = currentOrderRank != null && targetOrderRank != null && targetOrderRank > currentOrderRank;
        boolean fulfillmentProgressed = currentFulfillmentRank != null
                && targetFulfillmentRank != null
                && targetFulfillmentRank > currentFulfillmentRank;
        return orderProgressed || fulfillmentProgressed;
    }

    private void updateOrderStatuses(UUID orderId, String orderStatus, String paymentStatus, String fulfillmentStatus, Instant now) {
        jdbcTemplate.update("""
                UPDATE customer_orders
                SET status = :status,
                    payment_status = :paymentStatus,
                    fulfillment_status = :fulfillmentStatus,
                    updated_at = :updatedAt
                WHERE id = :orderId
                """, new MapSqlParameterSource()
                .addValue("status", orderStatus)
                .addValue("paymentStatus", paymentStatus)
                .addValue("fulfillmentStatus", fulfillmentStatus)
                .addValue("updatedAt", Timestamp.from(now))
                .addValue("orderId", orderId));
    }

    private void insertStatusEvent(UUID orderId, String eventType, String fromStatus, String toStatus, String actorType, String actorId, String note, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO customer_order_status_events (
                    order_id, event_type, from_status, to_status, actor_type, actor_id, note, created_at
                )
                VALUES (
                    :orderId, :eventType, :fromStatus, :toStatus, :actorType, :actorId, :note, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("eventType", eventType)
                .addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus)
                .addValue("actorType", actorType)
                .addValue("actorId", actorId)
                .addValue("note", note)
                .addValue("createdAt", Timestamp.from(createdAt)));
    }

    private MutableOrderRow findMutableOrder(String orderNumber) {
        return jdbcTemplate.query("""
                SELECT id, customer_id, order_number, status, payment_status, fulfillment_status
                FROM customer_orders
                WHERE order_number = :orderNumber
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("orderNumber", requiredOrderNumber(orderNumber)), (rs, rowNum) -> new MutableOrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status")
        )).stream().findFirst().orElseThrow(() -> new CustomerOrderNotFoundException(orderNumber));
    }

    private MutableOrderRow findMutableOrderForCustomer(UUID customerId, String orderNumber) {
        return jdbcTemplate.query("""
                SELECT id, customer_id, order_number, status, payment_status, fulfillment_status
                FROM customer_orders
                WHERE order_number = :orderNumber
                  AND customer_id = :customerId
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("orderNumber", requiredOrderNumber(orderNumber))
                .addValue("customerId", customerId), (rs, rowNum) -> new MutableOrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status")
        )).stream().findFirst().orElseThrow(() -> new CustomerOrderNotFoundException(orderNumber));
    }

    private OrderIdentity findOrderIdentity(String orderNumber) {
        return jdbcTemplate.query("""
                SELECT customer_id, order_number
                FROM customer_orders
                WHERE order_number = :orderNumber
                LIMIT 1
                """, new MapSqlParameterSource("orderNumber", requiredOrderNumber(orderNumber)), (rs, rowNum) -> new OrderIdentity(
                rs.getObject("customer_id", UUID.class),
                rs.getString("order_number")
        )).stream().findFirst().orElseThrow(() -> new CustomerOrderNotFoundException(orderNumber));
    }

    private static String requiredOrderNumber(String orderNumber) {
        String normalized = optionalTrim(orderNumber);
        if (normalized == null) {
            throw new CustomerOrderPlacementException("Order number is required.");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizedOrCurrent(String candidate, String current) {
        String normalized = optionalTrim(candidate);
        return normalized == null ? current : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizedFilter(String value) {
        String normalized = optionalTrim(value);
        return normalized == null ? "" : normalized;
    }

    private static String optionalTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String maybePromotePayment(String currentStatus, String targetStatus) {
        if (currentStatus.equals(targetStatus)) {
            return currentStatus;
        }
        boolean allowed = switch (currentStatus) {
            case "PENDING" -> Set.of("AUTHORIZED", "CAPTURED", "FAILED").contains(targetStatus);
            case "AUTHORIZED" -> Set.of("CAPTURED", "FAILED").contains(targetStatus);
            case "CAPTURED" -> "REFUNDED".equals(targetStatus);
            case "FAILED", "REFUNDED" -> false;
            default -> false;
        };
        return allowed ? targetStatus : currentStatus;
    }

    @SuppressWarnings("unused")
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CustomerOrderPlacementException("Order metadata serialization failed.");
        }
    }

    private record MutableOrderRow(
            UUID id,
            UUID customerId,
            String orderNumber,
            String orderStatus,
            String paymentStatus,
            String fulfillmentStatus
    ) {
    }

    private record OrderIdentity(UUID customerId, String orderNumber) {
    }

    public record PaymentWebhookOrderUpdateResult(
            boolean changed,
            String orderNumber,
            String orderStatus,
            String paymentStatus,
            String fulfillmentStatus,
            String message
    ) {
    }
}

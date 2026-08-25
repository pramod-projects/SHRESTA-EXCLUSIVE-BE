package com.shrestaexclusive.platform.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.email.application.EmailNotificationCommand;
import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.email.domain.NotificationType;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCheckoutService;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCheckoutUpstreamException;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCreateRefundResponse;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayRefundStatusResponse;

@Service
public class CustomerOrderLifecycleService {

    private static final Set<String> TERMINAL_ORDER_STATUSES = Set.of("DELIVERED", "CANCELLED", "PAYMENT_FAILED");
    private static final Set<String> TERMINAL_FULFILLMENT_STATUSES = Set.of("DELIVERED", "CANCELLED");
    private static final Set<String> SUCCESSFUL_RAZORPAY_REFUND_STATUSES = Set.of("PROCESSED", "REFUNDED");
    private static final Pattern REFUND_ID_PATTERN = Pattern.compile("\\[refund-id:\\s*([^,\\]]+)", Pattern.CASE_INSENSITIVE);
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
    private final EmailNotificationService emailNotificationService;
    private final RazorpayCheckoutService razorpayCheckoutService;
    private final RefundPolicyConfigurationService refundPolicyConfigurationService;
    private final Clock clock;

    @Autowired
    public CustomerOrderLifecycleService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CustomerOrderService customerOrderService,
            EmailNotificationService emailNotificationService,
            RazorpayCheckoutService razorpayCheckoutService,
            RefundPolicyConfigurationService refundPolicyConfigurationService
    ) {
        this(jdbcTemplate, customerOrderService, emailNotificationService, razorpayCheckoutService,
                refundPolicyConfigurationService, Clock.systemUTC());
    }

    CustomerOrderLifecycleService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CustomerOrderService customerOrderService,
            EmailNotificationService emailNotificationService,
            RazorpayCheckoutService razorpayCheckoutService,
            RefundPolicyConfigurationService refundPolicyConfigurationService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerOrderService = customerOrderService;
        this.emailNotificationService = emailNotificationService;
        this.razorpayCheckoutService = razorpayCheckoutService;
        this.refundPolicyConfigurationService = refundPolicyConfigurationService;
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
                                             CASE
                                                     WHEN order_row.payment_status = 'REFUNDED' THEN 'SUCCESS'
                                                     WHEN EXISTS (
                                                             SELECT 1
                                                             FROM customer_order_status_events event_row
                                                             WHERE event_row.order_id = order_row.id
                                                                 AND event_row.event_type = 'PAYMENT_STATUS'
                                                                 AND event_row.to_status = 'REFUND_INITIATED'
                                                     ) THEN 'PROCESSING'
                                                     WHEN EXISTS (
                                                             SELECT 1
                                                             FROM customer_order_status_events event_row
                                                             WHERE event_row.order_id = order_row.id
                                                                 AND event_row.event_type = 'PAYMENT_STATUS'
                                                                 AND event_row.to_status = 'REFUND_REQUESTED'
                                                     ) THEN 'REQUESTED'
                                                     ELSE 'NONE'
                                             END AS refund_request_status,
                        order_row.delivery_mode,
                        order_row.payment_method,
                        order_row.total_paise,
                        order_row.placed_at,
                        order_row.is_test,
                        COALESCE(SUM(item.quantity), 0)::int AS item_count
                 FROM customer_orders order_row
                 JOIN customer_accounts account ON account.id = order_row.customer_id
                 LEFT JOIN customer_order_items item ON item.order_id = order_row.id
                 WHERE (:customerEmail = '' OR order_row.customer_email = :customerEmail)
                   AND (:orderNumber = '' OR order_row.order_number = :orderNumber)
                 GROUP BY order_row.id, order_row.order_number, order_row.customer_id, order_row.customer_email,
                          account.display_name, order_row.status, order_row.payment_status, order_row.fulfillment_status,
                          order_row.delivery_mode, order_row.payment_method, order_row.total_paise, order_row.placed_at,
                          order_row.is_test
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
                rs.getString("refund_request_status"),
                rs.getString("delivery_mode"),
                rs.getString("payment_method"),
                rs.getLong("total_paise"),
                rs.getInt("item_count"),
                rs.getTimestamp("placed_at").toInstant(),
                rs.getBoolean("is_test")
        ));
    }

    @Transactional
    public CustomerOrderResponse requestRefundByCustomer(UUID customerId, String orderNumber, String note) {
        MutableOrderRow order = findMutableOrderForCustomer(customerId, orderNumber);

        if ("REFUNDED".equals(order.paymentStatus())) {
            throw new CustomerOrderPlacementException("Refund is already completed for this order.");
        }
        if (!"CAPTURED".equals(order.paymentStatus())) {
            throw new CustomerOrderPlacementException("Refund can be requested only after payment is captured.");
        }
        if (!"DELIVERED".equals(order.orderStatus()) && !"DELIVERED".equals(order.fulfillmentStatus())) {
            throw new CustomerOrderPlacementException("Refund can be requested only after order delivery.");
        }
        validateRefundWindow(order.id(), Instant.now(clock));
        if ("CANCELLED".equals(order.orderStatus()) || "PAYMENT_FAILED".equals(order.orderStatus())) {
            throw new CustomerOrderPlacementException("Refund cannot be requested for this order status.");
        }
        if (hasOpenRefundRequest(order.id(), order.paymentStatus())) {
            return customerOrderService.findOrderForCustomer(customerId, order.orderNumber());
        }

        Instant now = Instant.now(clock);
        String normalizedNote = optionalTrim(note);
        insertStatusEvent(
                order.id(),
                "PAYMENT_STATUS",
                order.paymentStatus(),
                "REFUND_REQUESTED",
                "CUSTOMER",
                customerId.toString(),
                normalizedNote == null ? "Customer requested refund." : normalizedNote,
                now
        );

        return customerOrderService.findOrderForCustomer(customerId, order.orderNumber());
    }

    @Transactional
    public CustomerOrderResponse approveRefundByAdmin(String orderNumber, AdminOrderRefundApprovalRequest request, String actorId) {
        MutableOrderRow order = findMutableOrder(orderNumber);

        if ("REFUNDED".equals(order.paymentStatus())) {
            throw new CustomerOrderPlacementException("Refund is already completed for this order.");
        }
        if (!"CAPTURED".equals(order.paymentStatus())) {
            throw new CustomerOrderPlacementException("Refund approval is allowed only when payment status is CAPTURED.");
        }
        if (!hasRefundRequest(order.id())) {
            throw new CustomerOrderPlacementException("Customer refund request was not found for this order.");
        }
        if (hasRefundInitiated(order.id())) {
            throw new CustomerOrderPlacementException("Refund is already initiated and waiting for Razorpay confirmation.");
        }

        String paymentId = findRefundPaymentId(order.id(), order.orderNumber());
        if (paymentId == null) {
            throw new CustomerOrderPlacementException("Razorpay payment id is missing for this order. Refund cannot be initiated.");
        }

        String note = composeAdminNote(optionalTrim(request.note()), optionalTrim(request.opsReference()), "Admin approved customer refund request.");
        RazorpayCreateRefundResponse refundResponse;
        if (order.isTest()) {
            refundResponse = new RazorpayCreateRefundResponse(
                    "rfnd_test_" + UUID.randomUUID(),
                    paymentId,
                    "PROCESSED",
                    order.totalPaise(),
                    "INR"
            );
        } else {
            try {
                refundResponse = razorpayCheckoutService.createRefund(paymentId, order.totalPaise(), note);
            } catch (RazorpayCheckoutUpstreamException exception) {
                throw new CustomerOrderPlacementException(exception.getMessage());
            }
        }

        Instant now = Instant.now(clock);
        insertStatusEvent(
                order.id(),
                "PAYMENT_STATUS",
                order.paymentStatus(),
                "REFUND_INITIATED",
                "ADMIN",
                actorId,
                note
                        + " [refund-id: " + refundResponse.refundId()
                        + ", status: " + refundResponse.status()
                        + ", payment-id: " + refundResponse.paymentId() + "]",
                now
        );
            enqueueOrderNotification(order, NotificationType.REFUND_INITIATED, "refund:initiated");

        return customerOrderService.findOrderForCustomer(order.customerId(), order.orderNumber());
    }

    @Transactional
    public AdminOrderRefundStatusResponse checkAndSyncRefundStatusByAdmin(String orderNumber, String actorId) {
        MutableOrderRow order = findMutableOrder(orderNumber);
        String refundId = findLatestRefundId(order.id());
        if (refundId == null) {
            return new AdminOrderRefundStatusResponse(
                    order.orderNumber(),
                    null,
                    "UNKNOWN",
                    false,
                    false,
                    "Refund initiation record was not found for this order. Approve refund first."
            );
        }

        RazorpayRefundStatusResponse refundStatusResponse;
        if (order.isTest()) {
            refundStatusResponse = new RazorpayRefundStatusResponse(
                    refundId,
                    findRefundPaymentId(order.id(), order.orderNumber()),
                    "PROCESSED",
                    order.totalPaise(),
                    "INR"
            );
        } else {
            try {
                refundStatusResponse = razorpayCheckoutService.fetchRefundStatus(refundId);
            } catch (RazorpayCheckoutUpstreamException exception) {
                throw new CustomerOrderPlacementException(exception.getMessage());
            }
        }

        String razorpayStatus = refundStatusResponse.status() == null
                ? "UNKNOWN"
                : refundStatusResponse.status().trim().toUpperCase(Locale.ROOT);
        boolean successful = SUCCESSFUL_RAZORPAY_REFUND_STATUSES.contains(razorpayStatus);

        if (!successful) {
            return new AdminOrderRefundStatusResponse(
                    order.orderNumber(),
                    refundStatusResponse.refundId(),
                    razorpayStatus,
                    false,
                    false,
                    "Razorpay refund is currently " + razorpayStatus + ". Payment status will stay unchanged."
            );
        }

        String paymentId = optionalTrim(refundStatusResponse.paymentId());
        if (paymentId == null) {
            paymentId = findRefundPaymentId(order.id(), order.orderNumber());
        }
        if (paymentId != null) {
            upsertRefundedPaymentTransaction(
                    paymentId,
                    order.orderNumber(),
                    refundStatusResponse.amount(),
                    refundStatusResponse.currency(),
                    refundStatusResponse.refundId()
            );
        }

        if ("REFUNDED".equals(order.paymentStatus())) {
            return new AdminOrderRefundStatusResponse(
                    order.orderNumber(),
                    refundStatusResponse.refundId(),
                    razorpayStatus,
                    true,
                    false,
                    "Razorpay refund is successful and payment was already marked REFUNDED."
            );
        }

        Instant now = Instant.now(clock);
        updateOrderStatuses(order.id(), order.orderStatus(), "REFUNDED", order.fulfillmentStatus(), now);
        customerOrderService.restockOrderItems(order.id());
        insertStatusEvent(
                order.id(),
                "PAYMENT_STATUS",
                order.paymentStatus(),
                "REFUNDED",
                "ADMIN",
                actorId,
                "Admin reconciled Razorpay refund success. [refund-id: " + refundStatusResponse.refundId()
                        + ", status: " + razorpayStatus + "]",
                now
        );
            enqueueOrderNotification(order, NotificationType.REFUND_COMPLETED, "refund:completed");

        return new AdminOrderRefundStatusResponse(
                order.orderNumber(),
                refundStatusResponse.refundId(),
                razorpayStatus,
                true,
                true,
                "Razorpay refund is successful. Payment status updated to REFUNDED."
        );
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
                 WHERE account.is_test = FALSE
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
    public CustomerOrderResponse updateOrderStatusesByAdmin(String orderNumber, AdminOrderStatusUpdateRequest request, String actorId) {
        MutableOrderRow order = findMutableOrder(orderNumber);

        String requestedFulfillmentWorkflowStatus = optionalTrim(request.fulfillmentStatus());
        if (requestedFulfillmentWorkflowStatus == null) {
            throw new CustomerOrderPlacementException("Fulfillment status update is required.");
        }

        String normalizedRequestedFulfillmentWorkflowStatus = requestedFulfillmentWorkflowStatus.toUpperCase(Locale.ROOT);
        String targetOrderStatus;
        String targetFulfillmentStatus;
        switch (normalizedRequestedFulfillmentWorkflowStatus) {
            case "PENDING" -> {
                targetOrderStatus = "CONFIRMED";
                targetFulfillmentStatus = "PENDING";
            }
            case "PACKING" -> {
                targetOrderStatus = "PACKING";
                targetFulfillmentStatus = "PACKING";
            }
            case "OUT_FOR_DELIVERY" -> {
                targetOrderStatus = "OUT_FOR_DELIVERY";
                targetFulfillmentStatus = "SHIPPED";
            }
            case "DELIVERED" -> {
                targetOrderStatus = "DELIVERED";
                targetFulfillmentStatus = "DELIVERED";
            }
            default -> throw new CustomerOrderPlacementException("Unsupported fulfillment workflow stage.");
        }
        String targetPaymentStatus = order.paymentStatus();

        if ("PENDING".equals(normalizedRequestedFulfillmentWorkflowStatus)
                && !Set.of("PLACED", "PAYMENT_PENDING", "CONFIRMED").contains(order.orderStatus())) {
            throw new CustomerOrderPlacementException("PENDING stage is allowed only when order is in initial confirmed flow.");
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

        if (!"REFUNDED".equals(order.paymentStatus()) && "REFUNDED".equals(targetPaymentStatus)) {
            customerOrderService.restockOrderItems(order.id());
        }

        String note = optionalTrim(request.note());
        String opsReference = optionalTrim(request.opsReference());
        if (!order.orderStatus().equals(targetOrderStatus)) {
            insertStatusEvent(order.id(), "ORDER_STATUS", order.orderStatus(), targetOrderStatus, "ADMIN", actorId,
                composeAdminNote(note, opsReference, "Admin updated order status from fulfillment workflow."), now);
        }
        if (!order.fulfillmentStatus().equals(targetFulfillmentStatus)) {
            insertStatusEvent(order.id(), "FULFILLMENT_STATUS", order.fulfillmentStatus(), targetFulfillmentStatus, "ADMIN", actorId,
                composeAdminNote(note, opsReference, "Admin updated fulfillment status."), now);
        }
        if ("OUT_FOR_DELIVERY".equals(targetOrderStatus)) {
            enqueueOrderNotification(order, NotificationType.ORDER_OUT_FOR_DELIVERY, "fulfillment:out-for-delivery");
        }
        if ("DELIVERED".equals(targetOrderStatus)) {
            enqueueOrderNotification(order, NotificationType.ORDER_DELIVERED, "fulfillment:delivered");
        }

        return customerOrderService.findOrderForCustomer(order.customerId(), order.orderNumber());
    }

    private void enqueueOrderNotification(MutableOrderRow order, NotificationType type, String eventKey) {
        if (order.isTest()) {
            return;
        }
        Map<String, String> variables = switch (type) {
            case PAYMENT_SUCCESS, REFUND_INITIATED, REFUND_COMPLETED -> Map.of(
                "customerName", order.customerName(),
                "orderNumber", order.orderNumber(),
                "total", "INR %.2f".formatted(order.totalPaise() / 100.0)
            );
            case ORDER_OUT_FOR_DELIVERY, ORDER_DELIVERED, PAYMENT_FAILED -> Map.of(
                "customerName", order.customerName(),
                "orderNumber", order.orderNumber()
            );
            default -> throw new IllegalArgumentException("Unsupported order lifecycle notification: " + type);
        };
        emailNotificationService.enqueue(new EmailNotificationCommand(
                type,
                order.customerEmail(),
            variables,
                "order:" + order.orderNumber() + ":" + eventKey,
                order.customerId(),
                order.id().toString()
        ));
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

        if (!"REFUNDED".equals(order.paymentStatus()) && "REFUNDED".equals(targetPaymentStatus)) {
            customerOrderService.restockOrderItems(order.id());
        }

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
        if (!order.paymentStatus().equals(targetPaymentStatus)) {
            switch (targetPaymentStatus) {
                case "CAPTURED" -> enqueueOrderNotification(order, NotificationType.PAYMENT_SUCCESS, "payment:captured");
                case "FAILED" -> enqueueOrderNotification(order, NotificationType.PAYMENT_FAILED, "payment:failed");
                case "REFUNDED" -> enqueueOrderNotification(order, NotificationType.REFUND_COMPLETED, "refund:completed");
                default -> {
                }
            }
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

        private boolean hasOpenRefundRequest(UUID orderId, String paymentStatus) {
                if ("REFUNDED".equals(paymentStatus)) {
                        return false;
                }
                return hasRefundRequest(orderId) || hasRefundInitiated(orderId);
        }

        private boolean hasRefundRequest(UUID orderId) {
                Integer count = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)::int
                                FROM customer_order_status_events
                                WHERE order_id = :orderId
                                    AND event_type = 'PAYMENT_STATUS'
                                    AND to_status = 'REFUND_REQUESTED'
                                """, new MapSqlParameterSource("orderId", orderId), Integer.class);
                return count != null && count > 0;
        }

        private boolean hasRefundInitiated(UUID orderId) {
                Integer count = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)::int
                                FROM customer_order_status_events
                                WHERE order_id = :orderId
                                    AND event_type = 'PAYMENT_STATUS'
                                    AND to_status = 'REFUND_INITIATED'
                                """, new MapSqlParameterSource("orderId", orderId), Integer.class);
                return count != null && count > 0;
        }

        private String findRefundPaymentId(UUID orderId, String orderNumber) {
                String paymentIdFromMetadata = jdbcTemplate.query("""
                                SELECT metadata ->> 'razorpayPaymentId' AS payment_id
                                FROM customer_orders
                                WHERE id = :orderId
                                LIMIT 1
                                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> optionalTrim(rs.getString("payment_id")))
                                .stream()
                                .findFirst()
                                .orElse(null);
                if (paymentIdFromMetadata != null) {
                        return paymentIdFromMetadata;
                }

                return jdbcTemplate.query("""
                                SELECT payment_id
                                FROM customer_payment_transactions
                                WHERE provider = 'RAZORPAY'
                                    AND order_number = :orderNumber
                                    AND payment_status IN ('CAPTURED', 'AUTHORIZED', 'REFUNDED')
                                ORDER BY updated_at DESC
                                LIMIT 1
                                """, new MapSqlParameterSource("orderNumber", orderNumber), (rs, rowNum) -> optionalTrim(rs.getString("payment_id")))
                                .stream()
                                .findFirst()
                                .orElse(null);
        }

            private String findLatestRefundId(UUID orderId) {
                return jdbcTemplate.query("""
                        SELECT note
                        FROM customer_order_status_events
                        WHERE order_id = :orderId
                            AND event_type = 'PAYMENT_STATUS'
                            AND to_status = 'REFUND_INITIATED'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> extractRefundId(optionalTrim(rs.getString("note"))))
                        .stream()
                        .findFirst()
                        .orElse(null);
            }

            private void upsertRefundedPaymentTransaction(
                String paymentId,
                String orderNumber,
                Long amountMinor,
                String currency,
                String refundId
            ) {
                Timestamp now = Timestamp.from(Instant.now(clock));
                jdbcTemplate.update("""
                        INSERT INTO customer_payment_transactions (
                            provider,
                            payment_id,
                            order_number,
                            latest_event_type,
                            latest_provider_event_id,
                            payment_status,
                            amount_minor,
                            currency,
                            is_captured,
                            captured_at,
                            last_webhook_received_at,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            'RAZORPAY',
                            :paymentId,
                            :orderNumber,
                            :eventType,
                            :providerEventId,
                            'REFUNDED',
                            :amountMinor,
                            :currency,
                            true,
                            :capturedAt,
                            :lastWebhookReceivedAt,
                            :createdAt,
                            :updatedAt
                        )
                        ON CONFLICT (provider, payment_id)
                        DO UPDATE SET
                            order_number = COALESCE(EXCLUDED.order_number, customer_payment_transactions.order_number),
                            latest_event_type = EXCLUDED.latest_event_type,
                            latest_provider_event_id = COALESCE(EXCLUDED.latest_provider_event_id, customer_payment_transactions.latest_provider_event_id),
                            payment_status = EXCLUDED.payment_status,
                            amount_minor = COALESCE(EXCLUDED.amount_minor, customer_payment_transactions.amount_minor),
                            currency = COALESCE(EXCLUDED.currency, customer_payment_transactions.currency),
                            is_captured = COALESCE(EXCLUDED.is_captured, customer_payment_transactions.is_captured),
                            captured_at = COALESCE(EXCLUDED.captured_at, customer_payment_transactions.captured_at),
                            last_webhook_received_at = EXCLUDED.last_webhook_received_at,
                            updated_at = EXCLUDED.updated_at
                        """, new MapSqlParameterSource()
                        .addValue("paymentId", paymentId)
                        .addValue("orderNumber", orderNumber)
                        .addValue("eventType", "payment.refunded.manual-sync")
                        .addValue("providerEventId", optionalTrim(refundId))
                        .addValue("amountMinor", amountMinor)
                        .addValue("currency", currency == null ? null : currency.toUpperCase(Locale.ROOT))
                        .addValue("capturedAt", now)
                        .addValue("lastWebhookReceivedAt", now)
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now));
            }

            private static String extractRefundId(String note) {
                if (note == null) {
                    return null;
                }
                Matcher matcher = REFUND_ID_PATTERN.matcher(note);
                if (!matcher.find()) {
                    return null;
                }
                return optionalTrim(matcher.group(1));
            }

    private MutableOrderRow findMutableOrder(String orderNumber) {
        return jdbcTemplate.query("""
            SELECT order_row.id, order_row.customer_id, order_row.customer_email, account.display_name,
                   order_row.order_number, order_row.status, order_row.payment_status,
                   order_row.fulfillment_status, order_row.total_paise, order_row.is_test
                FROM customer_orders order_row
                JOIN customer_accounts account ON account.id = order_row.customer_id
                WHERE order_number = :orderNumber
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("orderNumber", requiredOrderNumber(orderNumber)), (rs, rowNum) -> new MutableOrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_email"),
                rs.getString("display_name"),
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getString("payment_status"),
            rs.getString("fulfillment_status"),
            rs.getLong("total_paise"),
            rs.getBoolean("is_test")
        )).stream().findFirst().orElseThrow(() -> new CustomerOrderNotFoundException(orderNumber));
    }

    private MutableOrderRow findMutableOrderForCustomer(UUID customerId, String orderNumber) {
        return jdbcTemplate.query("""
              SELECT order_row.id, order_row.customer_id, order_row.customer_email, account.display_name,
                  order_row.order_number, order_row.status, order_row.payment_status,
                  order_row.fulfillment_status, order_row.total_paise, order_row.is_test
              FROM customer_orders order_row
              JOIN customer_accounts account ON account.id = order_row.customer_id
                WHERE order_number = :orderNumber
                  AND customer_id = :customerId
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("orderNumber", requiredOrderNumber(orderNumber))
                .addValue("customerId", customerId), (rs, rowNum) -> new MutableOrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_email"),
                rs.getString("display_name"),
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getString("fulfillment_status"),
                rs.getLong("total_paise"),
                rs.getBoolean("is_test")
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

    private static String composeAdminNote(String note, String opsReference, String fallback) {
        String base = note == null ? fallback : note;
        if (opsReference == null) {
            return base;
        }
        return base + " [ops-ref: " + opsReference + "]";
    }

    private void validateRefundWindow(UUID orderId, Instant now) {
        Instant deliveredAt = findDeliveredAt(orderId);
        if (deliveredAt == null) {
            return;
        }
        int eligibilityDays = refundPolicyConfigurationService.current().eligibilityDays();
        Instant refundWindowEndsAt = deliveredAt.plus(Duration.ofDays(eligibilityDays));
        if (now.isAfter(refundWindowEndsAt)) {
            throw new CustomerOrderPlacementException(
                    "Refund is allowed only within " + eligibilityDays + " days of delivery.");
        }
    }

    private Instant findDeliveredAt(UUID orderId) {
        return jdbcTemplate.query("""
                SELECT MIN(created_at) AS delivered_at
                FROM customer_order_status_events
                WHERE order_id = :orderId
                  AND (
                    (event_type = 'ORDER_STATUS' AND to_status = 'DELIVERED')
                    OR (event_type = 'FULFILLMENT_STATUS' AND to_status = 'DELIVERED')
                  )
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> {
            Timestamp deliveredAt = rs.getTimestamp("delivered_at");
            return deliveredAt == null ? null : deliveredAt.toInstant();
        }).stream().findFirst().orElse(null);
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

    private record MutableOrderRow(
            UUID id,
            UUID customerId,
            String customerEmail,
            String customerName,
            String orderNumber,
            String orderStatus,
            String paymentStatus,
            String fulfillmentStatus,
            long totalPaise,
            boolean isTest
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

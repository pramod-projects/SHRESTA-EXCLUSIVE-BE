package com.shrestaexclusive.platform.order;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
)
class AdminOrderStatusUpdateIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
        private CustomerOrderLifecycleService lifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminUpdatePersistsOpsReferenceInStatusEventNotes() throws Exception {
        UUID customerId = seededCustomerId();
        UUID orderId = UUID.randomUUID();
        String orderNumber = "SHRESTA-20260808-OPSREF17";

        jdbcTemplate.update("""
                INSERT INTO customer_orders (
                    id, order_number, customer_id, customer_email, status, payment_status, fulfillment_status,
                    currency, subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise,
                    delivery_mode, payment_method, contact_snapshot, shipping_address_snapshot, metadata,
                    placed_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, 'PLACED', 'CAPTURED', 'PENDING',
                    'INR', 10000, 0, 0, 0, 10000,
                    'STANDARD', 'UPI',
                    CAST(? AS jsonb), CAST(? AS jsonb), '{}'::jsonb,
                    now(), now(), now()
                )
                """,
                orderId,
                orderNumber,
                customerId,
                "testuser@gmail.com",
                "{\"email\":\"testuser@gmail.com\",\"phone\":\"9876543210\"}",
                "{\"fullName\":\"Test User\",\"phone\":\"9876543210\",\"addressLine1\":\"Line 1\",\"addressLine2\":\"\",\"landmark\":\"\",\"city\":\"Bengaluru\",\"state\":\"Karnataka\",\"postalCode\":\"560001\",\"country\":\"India\",\"addressType\":\"HOME\"}"
        );

        CustomerOrderResponse response = lifecycleService.updateOrderStatusesByAdmin(
                orderNumber,
                new AdminOrderStatusUpdateRequest(
                        "PACKING",
                        "Manual SHRESTA ops: packing started.",
                        "RUNNER-17"
                ),
                "CHANGE_MANAGER"
        );
        assertThat(response.orderStatus()).isEqualTo("PACKING");
        assertThat(response.fulfillmentStatus()).isEqualTo("PACKING");

        Map<String, Object> eventRow = jdbcTemplate.queryForMap("""
                SELECT note
                FROM customer_order_status_events
                WHERE order_id = ?
                  AND event_type = 'ORDER_STATUS'
                ORDER BY created_at DESC
                LIMIT 1
                """, orderId);

        String note = String.valueOf(eventRow.get("note"));
        assertThat(note).contains("Manual SHRESTA ops: packing started.");
        assertThat(note).contains("[ops-ref: RUNNER-17]");
    }

        @Test
        void adminPendingWorkflowKeepsOrderInConfirmedFlow() {
                UUID customerId = seededCustomerId();
                UUID orderId = UUID.randomUUID();
                String orderNumber = "SHRESTA-20260808-CANADM11";

                insertOrder(orderId, orderNumber, customerId, "PLACED", "PENDING", "PENDING");

                CustomerOrderResponse response = lifecycleService.updateOrderStatusesByAdmin(
                                orderNumber,
                                new AdminOrderStatusUpdateRequest(
                                                "PENDING",
                                                "Manual SHRESTA pending confirmation.",
                                                "OPS-PEND-1"
                                ),
                                "CHANGE_MANAGER"
                );

                assertThat(response.orderStatus()).isEqualTo("CONFIRMED");
                assertThat(response.paymentStatus()).isEqualTo("PENDING");
                assertThat(response.fulfillmentStatus()).isEqualTo("PENDING");
        }

        @Test
        void adminOutForDeliveryEnqueuesOneLifecycleNotification() {
                UUID customerId = seededCustomerId();
                UUID orderId = UUID.randomUUID();
                String orderNumber = "SHRESTA-20260808-OUTDLV11";
                insertOrder(orderId, orderNumber, customerId, "PACKING", "CAPTURED", "PACKING");

                AdminOrderStatusUpdateRequest request = new AdminOrderStatusUpdateRequest(
                                "OUT_FOR_DELIVERY",
                                "Courier handoff complete.",
                                "OPS-SHIP-1"
                );
                lifecycleService.updateOrderStatusesByAdmin(orderNumber, request, "CHANGE_MANAGER");
                lifecycleService.updateOrderStatusesByAdmin(orderNumber, request, "CHANGE_MANAGER");

                assertThat(jdbcTemplate.queryForObject("""
                                SELECT count(*)
                                FROM email_outbox
                                WHERE notification_type = 'ORDER_OUT_FOR_DELIVERY'
                                                                                                                                        AND correlation_id = ?
                                """, Integer.class, orderId.toString())).isEqualTo(1);
        }

        @Test
        void adminDeliveredEnqueuesOneLifecycleNotification() {
                UUID customerId = seededCustomerId();
                UUID orderId = UUID.randomUUID();
                String orderNumber = "SHRESTA-20260808-DLVRD111";
                insertOrder(orderId, orderNumber, customerId, "OUT_FOR_DELIVERY", "CAPTURED", "SHIPPED");

                lifecycleService.updateOrderStatusesByAdmin(
                                orderNumber,
                                new AdminOrderStatusUpdateRequest("DELIVERED", "Delivered.", "OPS-DLV-1"),
                                "CHANGE_MANAGER"
                );

                assertNotificationCount(orderId, "ORDER_DELIVERED", 1);
        }

        @Test
        void razorpayCapturedAndFailedEnqueueTheirPaymentNotifications() {
                UUID customerId = seededCustomerId();
                UUID capturedOrderId = UUID.randomUUID();
                UUID failedOrderId = UUID.randomUUID();
                String capturedOrderNumber = "SHRESTA-20260808-PAYOK111";
                String failedOrderNumber = "SHRESTA-20260808-PAYFL111";
                insertOrder(capturedOrderId, capturedOrderNumber, customerId, "PAYMENT_PENDING", "PENDING", "PENDING");
                insertOrder(failedOrderId, failedOrderNumber, customerId, "PAYMENT_PENDING", "PENDING", "PENDING");

                lifecycleService.applyRazorpayPaymentWebhook(capturedOrderNumber, "payment.captured", "pay_ok", "evt_ok");
                lifecycleService.applyRazorpayPaymentWebhook(failedOrderNumber, "payment.failed", "pay_failed", "evt_failed");

                assertNotificationCount(capturedOrderId, "PAYMENT_SUCCESS", 1);
                assertNotificationCount(failedOrderId, "PAYMENT_FAILED", 1);
        }

        private void assertNotificationCount(UUID orderId, String notificationType, int expectedCount) {
                assertThat(jdbcTemplate.queryForObject("""
                                SELECT count(*)
                                FROM email_outbox
                                WHERE notification_type = ?
                                  AND correlation_id = ?
                                """, Integer.class, notificationType, orderId.toString())).isEqualTo(expectedCount);
        }

        private void insertOrder(UUID orderId, String orderNumber, UUID customerId, String status, String paymentStatus, String fulfillmentStatus) {
                jdbcTemplate.update("""
                                INSERT INTO customer_orders (
                                        id, order_number, customer_id, customer_email, status, payment_status, fulfillment_status,
                                        currency, subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise,
                                        delivery_mode, payment_method, contact_snapshot, shipping_address_snapshot, metadata,
                                        placed_at, created_at, updated_at
                                ) VALUES (
                                        ?, ?, ?, ?, ?, ?, ?,
                                        'INR', 10000, 0, 0, 0, 10000,
                                        'STANDARD', 'UPI',
                                        CAST(? AS jsonb), CAST(? AS jsonb), '{}'::jsonb,
                                        now(), now(), now()
                                )
                                """,
                                orderId,
                                orderNumber,
                                customerId,
                                "testuser@gmail.com",
                                status,
                                paymentStatus,
                                fulfillmentStatus,
                                "{\"email\":\"testuser@gmail.com\",\"phone\":\"9876543210\"}",
                                "{\"fullName\":\"Test User\",\"phone\":\"9876543210\",\"addressLine1\":\"Line 1\",\"addressLine2\":\"\",\"landmark\":\"\",\"city\":\"Bengaluru\",\"state\":\"Karnataka\",\"postalCode\":\"560001\",\"country\":\"India\",\"addressType\":\"HOME\"}"
                );
        }

    private UUID seededCustomerId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customer_accounts WHERE primary_email = 'testuser@gmail.com' LIMIT 1",
                UUID.class
        );
    }

}

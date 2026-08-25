package com.shrestaexclusive.platform.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shrestaexclusive.platform.auth.AuthenticatedCustomer;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCheckoutService;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCreateOrderResponse;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCreateRefundResponse;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
)
class CustomerOrderStockLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private CustomerOrderService customerOrderService;

    @Autowired
    private CustomerOrderLifecycleService lifecycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RazorpayCheckoutService razorpayCheckoutService;

    @AfterEach
    @SuppressWarnings("unused")
    void restoreRefundPolicyDefault() {
        jdbcTemplate.update("""
                UPDATE refund_policy_configuration
                SET eligibility_days = 3
                WHERE policy_key = 'CUSTOMER_REFUND'
                """);
    }

        @Test
        void razorpayOrderUsesServerOwnedDraftTotalAndIsReused() {
        UUID customerId = seededCustomerId();
        AuthenticatedCustomer customer = authenticatedCustomer(customerId, "testuser@gmail.com", "SHRESTA UAT Test User");
        String productId = insertProductWithStock(1);
        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
            customer,
            new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );
        RazorpayCreateOrderResponse providerOrder = new RazorpayCreateOrderResponse(
            "order_server_owned_total", draft.totalPaise(), draft.currency());
        when(razorpayCheckoutService.createOrder(any())).thenReturn(providerOrder);

        RazorpayCreateOrderResponse first = customerOrderService.createRazorpayOrder(customer, draft.orderId());
        RazorpayCreateOrderResponse retry = customerOrderService.createRazorpayOrder(customer, draft.orderId());

        assertThat(first).isEqualTo(providerOrder);
        assertThat(retry).isEqualTo(providerOrder);
        verify(razorpayCheckoutService, times(1)).createOrder(argThat(request ->
            request.amount() == draft.totalPaise()
                && request.currency().equals(draft.currency())
                && request.receipt().equals(draft.orderNumber())));
        }

        @Test
        void placementRejectsRazorpayOrderNotBoundToDraft() {
        UUID customerId = seededCustomerId();
        AuthenticatedCustomer customer = authenticatedCustomer(customerId, "testuser@gmail.com", "SHRESTA UAT Test User");
        String productId = insertProductWithStock(1);
        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
            customer,
            new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );
        jdbcTemplate.update(
            "UPDATE customer_order_drafts SET metadata = metadata || jsonb_build_object('razorpayOrderId', ?) WHERE id = ?",
            "order_bound_to_draft",
            UUID.fromString(draft.orderId())
        );
        CustomerOrderPlacementRequest request = new CustomerOrderPlacementRequest(
            List.of(new CustomerOrderPlacementRequest.LineItem(productId, 1)),
            draft.orderId(),
            new CustomerOrderPlacementRequest.RazorpayPaymentProof("order_from_another_draft", "pay_123", "signature"),
            new CustomerOrderPlacementRequest.Contact("testuser@gmail.com", "9876543210"),
            new CustomerOrderPlacementRequest.ShippingAddress(
                "Test User", "9876543210", "1 Test Road", null, null,
                "Bengaluru", "Karnataka", "560001", "India", "HOME"),
            draft.deliveryMode(),
            "UPI",
            true
        );

        assertThatThrownBy(() -> customerOrderService.placeOrder(customer, request))
            .isInstanceOf(CustomerOrderPlacementException.class)
            .hasMessageContaining("does not match this checkout order ID");
        verify(razorpayCheckoutService, times(0)).verifyPayment(any());
        }

        @Test
        void razorpayOrderRejectsDraftOwnedByAnotherCustomer() {
        UUID ownerId = seededCustomerId();
        UUID otherCustomerId = insertCustomer("payment-owner-check@example.com", "Payment Owner Check");
        AuthenticatedCustomer owner = authenticatedCustomer(ownerId, "testuser@gmail.com", "SHRESTA UAT Test User");
        AuthenticatedCustomer otherCustomer = authenticatedCustomer(
            otherCustomerId, "payment-owner-check@example.com", "Payment Owner Check");
        String productId = insertProductWithStock(1);
        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
            owner,
            new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );

        assertThatThrownBy(() -> customerOrderService.createRazorpayOrder(otherCustomer, draft.orderId()))
            .isInstanceOf(CustomerOrderPlacementException.class)
            .hasMessageContaining("no longer belongs to this customer");
        verify(razorpayCheckoutService, times(0)).createOrder(any());
        }

    @Test
    void draftReservesStockAndPaymentFailureReleasesStock() {
        UUID customerId = seededCustomerId();
        AuthenticatedCustomer customer = authenticatedCustomer(customerId, "testuser@gmail.com", "SHRESTA UAT Test User");
        String productId = insertProductWithStock(1);

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );

        assertThat(currentStock(productId)).isEqualTo(0);

        customerOrderService.markDraftPaymentFailed(
                customer,
                draft.orderId(),
                new CustomerOrderDraftPaymentFailedRequest("payment.failed", null, null, "Payment did not complete")
        );

        assertThat(currentStock(productId)).isEqualTo(1);
    }

    @Test
    void expiredDraftReleasesReservationForAnotherCustomer() {
        UUID customerOneId = seededCustomerId();
        UUID customerTwoId = insertCustomer("stock-user-two@example.com", "Stock User Two");

        AuthenticatedCustomer customerOne = authenticatedCustomer(customerOneId, "testuser@gmail.com", "SHRESTA UAT Test User");
        AuthenticatedCustomer customerTwo = authenticatedCustomer(customerTwoId, "stock-user-two@example.com", "Stock User Two");
        String productId = insertProductWithStock(1);

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customerOne,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );

        assertThat(currentStock(productId)).isEqualTo(0);

        jdbcTemplate.update(
                """
                UPDATE customer_order_drafts
            SET created_at = ?,
                expires_at = ?,
                updated_at = ?
                WHERE id = ?
                """,
            Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)),
            Timestamp.from(Instant.now().minus(4, ChronoUnit.MINUTES)),
            Timestamp.from(Instant.now().minus(4, ChronoUnit.MINUTES)),
                UUID.fromString(draft.orderId())
        );

        CustomerOrderDraftResponse secondDraft = customerOrderService.createOrReuseDraft(
                customerTwo,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );

        assertThat(secondDraft.status()).isEqualTo("ACTIVE");
        assertThat(currentStock(productId)).isEqualTo(0);
    }

    @Test
    void refundRequestRejectedAfterThreeDaysFromDelivery() {
        UUID customerId = seededCustomerId();
        String orderNumber = validOrderNumber("RFNDLATE");
        UUID orderId = insertDeliveredOrder(customerId, orderNumber, "CAPTURED");

        insertStatusEvent(orderId, "ORDER_STATUS", "CONFIRMED", "DELIVERED", Instant.now().minus(4, ChronoUnit.DAYS));

        assertThatThrownBy(() -> lifecycleService.requestRefundByCustomer(customerId, orderNumber, "Requesting late refund"))
                .isInstanceOf(CustomerOrderPlacementException.class)
                .hasMessageContaining("Refund is allowed only within 3 days of delivery");
    }

            @Test
            void configuredRefundWindowControlsRuntimeEligibility() {
            UUID customerId = seededCustomerId();
            String orderNumber = validOrderNumber("RFNDCFG1");
            UUID orderId = insertDeliveredOrder(customerId, orderNumber, "CAPTURED");
            jdbcTemplate.update("""
                UPDATE refund_policy_configuration
                SET eligibility_days = 5
                WHERE policy_key = 'CUSTOMER_REFUND'
                """);
            insertStatusEvent(orderId, "ORDER_STATUS", "CONFIRMED", "DELIVERED", Instant.now().minus(6, ChronoUnit.DAYS));

            assertThatThrownBy(() -> lifecycleService.requestRefundByCustomer(customerId, orderNumber, "Requesting late refund"))
                .isInstanceOf(CustomerOrderPlacementException.class)
                .hasMessageContaining("Refund is allowed only within 5 days of delivery");
            }

    @Test
    void successfulRefundWebhookRestocksOrderItems() {
        UUID customerId = seededCustomerId();
        String productId = insertProductWithStock(0);
        String orderNumber = validOrderNumber("RFDWKHOK");
        UUID orderId = insertDeliveredOrder(customerId, orderNumber, "CAPTURED");

        jdbcTemplate.update(
                """
                INSERT INTO customer_order_items (
                    order_id, product_item_key, product_sku, product_slug, product_name,
                    family_key, product_type, quantity, unit_price_paise, compare_at_price_paise,
                    line_total_paise, media_asset_key, media_url, media_alt_text, metadata
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, null, null, null, '{}'::jsonb
                )
                """,
                orderId,
                productId,
                "SKU-REFUND-1",
                "refund-saree",
                "Refund Saree",
                "test-family",
                "SAREE",
                1,
                10000,
                12000,
                10000
        );

        lifecycleService.applyRazorpayPaymentWebhook(orderNumber, "payment.refunded", "pay_refund_1", "evt_refund_1");

        assertThat(currentStock(productId)).isEqualTo(1);
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT payment_status FROM customer_orders WHERE id = ?",
                String.class,
                orderId
        );
        assertThat(paymentStatus).isEqualTo("REFUNDED");
        assertNotificationCount(orderId, "REFUND_COMPLETED", 1);
        }

        @Test
        void approvedRefundEnqueuesInitiatedNotification() {
        UUID customerId = seededCustomerId();
        String orderNumber = validOrderNumber("RFDSTART");
        UUID orderId = insertDeliveredOrder(customerId, orderNumber, "CAPTURED");
        insertStatusEvent(orderId, "ORDER_STATUS", "OUT_FOR_DELIVERY", "DELIVERED", Instant.now());
        jdbcTemplate.update("""
            UPDATE customer_orders
            SET metadata = jsonb_build_object('razorpayPaymentId', 'pay_refund_start')
            WHERE id = ?
            """, orderId);
        when(razorpayCheckoutService.createRefund(anyString(), anyLong(), anyString()))
            .thenReturn(new RazorpayCreateRefundResponse(
                "rfnd_start", "pay_refund_start", "CREATED", 10000L, "INR"));

        lifecycleService.requestRefundByCustomer(customerId, orderNumber, "Please refund this order.");
        lifecycleService.approveRefundByAdmin(
            orderNumber,
            new AdminOrderRefundApprovalRequest("Approved.", "OPS-RFND-1"),
            "CHANGE_MANAGER"
        );

        assertNotificationCount(orderId, "REFUND_INITIATED", 1);
    }

    @Test
    void parallelCheckoutReservationDoesNotOversellStock() throws InterruptedException {
        UUID customerOneId = insertCustomer("parallel-user-one@example.com", "Parallel User One");
        UUID customerTwoId = insertCustomer("parallel-user-two@example.com", "Parallel User Two");
        AuthenticatedCustomer customerOne = authenticatedCustomer(customerOneId, "parallel-user-one@example.com", "Parallel User One");
        AuthenticatedCustomer customerTwo = authenticatedCustomer(customerTwoId, "parallel-user-two@example.com", "Parallel User Two");
        String productId = insertProductWithStock(1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger unavailableCount = new AtomicInteger();
        AtomicInteger unexpectedFailureCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> attemptParallelDraftReservation(customerOne, productId, ready, start, finished, successCount, unavailableCount, unexpectedFailureCount));
            pool.submit(() -> attemptParallelDraftReservation(customerTwo, productId, ready, start, finished, successCount, unavailableCount, unexpectedFailureCount));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(finished.await(8, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(unavailableCount.get()).isEqualTo(1);
        assertThat(unexpectedFailureCount.get()).isEqualTo(0);
        assertThat(currentStock(productId)).isEqualTo(0);
    }

    @Test
    void globalExpirySweepReleasesStaleReservationWithoutNewCheckoutTraffic() {
        UUID customerId = seededCustomerId();
        AuthenticatedCustomer customer = authenticatedCustomer(customerId, "testuser@gmail.com", "SHRESTA UAT Test User");
        String productId = insertProductWithStock(1);

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );

        assertThat(currentStock(productId)).isEqualTo(0);

        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                UPDATE customer_order_drafts
                SET created_at = ?,
                    expires_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                Timestamp.from(now.minus(20, ChronoUnit.MINUTES)),
                Timestamp.from(now.minus(5, ChronoUnit.MINUTES)),
                Timestamp.from(now.minus(5, ChronoUnit.MINUTES)),
                UUID.fromString(draft.orderId())
        );

        int releasedCount = customerOrderService.expireGloballyExpiredDrafts();

        assertThat(releasedCount).isEqualTo(1);
        assertThat(currentStock(productId)).isEqualTo(1);
        String draftStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM customer_order_drafts WHERE id = ?",
                String.class,
                UUID.fromString(draft.orderId())
        );
        assertThat(draftStatus).isEqualTo("EXPIRED");
    }

    private void attemptParallelDraftReservation(
            AuthenticatedCustomer customer,
            String productId,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch finished,
            AtomicInteger successCount,
            AtomicInteger unavailableCount,
            AtomicInteger unexpectedFailureCount
    ) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                unexpectedFailureCount.incrementAndGet();
                return;
            }

            customerOrderService.createOrReuseDraft(
                    customer,
                    new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
            );
            successCount.incrementAndGet();
        } catch (CustomerOrderProductUnavailableException expectedUnavailable) {
            unavailableCount.incrementAndGet();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            unexpectedFailureCount.incrementAndGet();
        } catch (RuntimeException unexpected) {
            unexpectedFailureCount.incrementAndGet();
        } finally {
            finished.countDown();
        }
    }

    private UUID seededCustomerId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customer_accounts WHERE primary_email = 'testuser@gmail.com' LIMIT 1",
                UUID.class
        );
    }

    private UUID insertCustomer(String email, String displayName) {
        UUID customerId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO customer_accounts (id, primary_email, display_name, status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', '{}'::jsonb, now(), now())
                """,
                customerId,
                email.toLowerCase(Locale.ROOT),
                displayName
        );
        return customerId;
    }

    private AuthenticatedCustomer authenticatedCustomer(UUID customerId, String email, String displayName) {
        return new AuthenticatedCustomer(
                customerId,
                email,
                displayName,
                "ACTIVE",
                Instant.now().plus(1, ChronoUnit.HOURS),
                false
        );
    }

    private String insertProductWithStock(int stockQuantity) {
        UUID sectionId = UUID.randomUUID();
        String sectionKey = "test_stock_section_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String productId = "test-stock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

        jdbcTemplate.update(
                """
                INSERT INTO storefront_home_sections (
                    id, section_key, section_type, eyebrow, title, description, sort_order, metadata, is_active, created_at, updated_at
                ) VALUES (
                    ?, ?, 'product_grid', 'test', 'Test Section', 'Stock lifecycle section', 0, '{}'::jsonb, TRUE, now(), now()
                )
                """,
                sectionId,
                sectionKey
        );

        String metadataJson = "{" +
                "\"sku\":\"SKU-" + productId + "\"," +
                "\"slug\":\"" + productId + "\"," +
                "\"productType\":\"SAREE\"," +
                "\"pricePaise\":10000," +
                "\"compareAtPricePaise\":12000," +
                "\"stockQuantity\":" + stockQuantity +
                "}";

        jdbcTemplate.update(
                """
                INSERT INTO storefront_home_items (
                    id, section_id, item_key, family_key, title, subtitle, description,
                    cta_label, cta_href, sort_order, is_featured, media_asset_id,
                    demo_video_url, metadata, is_active, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'test-family', 'Test Saree', 'subtitle', 'description',
                    'Shop now', '/products/test', 0, FALSE, null,
                    null, CAST(? AS jsonb), TRUE, now(), now()
                )
                """,
                UUID.randomUUID(),
                sectionId,
                productId,
                metadataJson
        );

        return productId;
    }

    private int currentStock(String productId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT COALESCE((metadata ->> 'stockQuantity')::int, 0) FROM storefront_home_items WHERE item_key = ?",
                Integer.class,
                productId
        );
        return stock == null ? 0 : stock;
    }

        private void assertNotificationCount(UUID orderId, String notificationType, int expectedCount) {
                assertThat(jdbcTemplate.queryForObject("""
                                SELECT count(*)
                                FROM email_outbox
                                WHERE notification_type = ?
                                    AND correlation_id = ?
                                """, Integer.class, notificationType, orderId.toString())).isEqualTo(expectedCount);
        }

    private UUID insertDeliveredOrder(UUID customerId, String orderNumber, String paymentStatus) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO customer_orders (
                    id, order_number, customer_id, customer_email, status, payment_status, fulfillment_status,
                    currency, subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise,
                    delivery_mode, payment_method, contact_snapshot, shipping_address_snapshot, metadata,
                    placed_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, 'DELIVERED', ?, 'DELIVERED',
                    'INR', 10000, 0, 0, 0, 10000,
                    'STANDARD', 'CARD',
                    CAST(? AS jsonb), CAST(? AS jsonb), '{}'::jsonb,
                    now(), now(), now()
                )
                """,
                orderId,
                orderNumber,
                customerId,
                "testuser@gmail.com",
                paymentStatus,
                "{\"email\":\"testuser@gmail.com\",\"phone\":\"9876543210\"}",
                "{\"fullName\":\"Test User\",\"phone\":\"9876543210\",\"addressLine1\":\"Line 1\",\"addressLine2\":\"\",\"landmark\":\"\",\"city\":\"Bengaluru\",\"state\":\"Karnataka\",\"postalCode\":\"560001\",\"country\":\"India\",\"addressType\":\"HOME\"}"
        );
        return orderId;
    }

    private void insertStatusEvent(UUID orderId, String eventType, String fromStatus, String toStatus, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO customer_order_status_events (
                    order_id, event_type, from_status, to_status, actor_type, actor_id, note, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'SYSTEM', 'TEST', 'test event', ?
                )
                """,
                orderId,
                eventType,
                fromStatus,
                toStatus,
                Timestamp.from(createdAt)
        );
    }

    private String validOrderNumber(String suffix) {
        return "SHRESTA-20260809-" + suffix;
    }
}

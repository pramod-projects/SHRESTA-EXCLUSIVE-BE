package com.shrestaexclusive.platform.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.shrestaexclusive.platform.email.application.EmailNotificationService;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCheckoutService;
import com.shrestaexclusive.platform.payment.razorpay.RazorpayCreateOrderResponse;
import com.shrestaexclusive.platform.sms.CustomerSmsDeliveryService;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets",
            "shresta.scheduling.enabled=false"
        }
)
@MockBean(classes = {
    RazorpayCheckoutService.class,
    EmailNotificationService.class,
    CustomerSmsDeliveryService.class
})
class CustomerOrderTestUserLaneIntegrationTest {

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

    @Autowired
    private RazorpayCheckoutService razorpayCheckoutService;

    @Autowired
    private EmailNotificationService emailNotificationService;

    @Autowired
    private CustomerSmsDeliveryService smsDeliveryService;

    @Test
    void testCustomerDraftCopiesFlagAndPaymentFailedReleaseIsStockNoOp() {
        AuthenticatedCustomer customer = insertTestCustomer("draft-flag");
        String productId = insertProductWithStock(5);

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 2)))
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_test FROM customer_order_drafts WHERE id = ?",
                Boolean.class,
                UUID.fromString(draft.orderId())
        )).isTrue();
        assertThat(currentStock(productId)).isEqualTo(5);

        customerOrderService.markDraftPaymentFailed(
                customer,
                draft.orderId(),
                new CustomerOrderDraftPaymentFailedRequest("payment.failed", null, null, "simulated decline")
        );

        assertThat(currentStock(productId)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM customer_order_drafts WHERE id = ?",
                String.class,
                UUID.fromString(draft.orderId())
        )).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void testCustomerExpirySweepsNeverDecrementOrPhantomRestock() {
        AuthenticatedCustomer customer = insertTestCustomer("draft-expiry");
        String productId = insertProductWithStock(3);

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 2)))
        );
        backdateDraftExpiry(draft.orderId());

        int expired = customerOrderService.expireGloballyExpiredDrafts();

        assertThat(expired).isEqualTo(1);
        assertThat(currentStock(productId)).isEqualTo(3);

        CustomerOrderDraftResponse replacement = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 2)))
        );

        assertThat(replacement.status()).isEqualTo("ACTIVE");
        assertThat(replacement.orderId()).isNotEqualTo(draft.orderId());
        assertThat(currentStock(productId)).isEqualTo(3);
    }

    @Test
    void testCustomerPlacementValidationReleasesAreStockNoOps() {
        AuthenticatedCustomer customer = insertTestCustomer("placement-release");
        String productId = insertProductWithStock(7);

        CustomerOrderDraftResponse expiredDraft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );
        RazorpayCreateOrderResponse simulated = customerOrderService.createRazorpayOrder(customer, expiredDraft.orderId());
        backdateDraftExpiry(expiredDraft.orderId());

        assertThatThrownBy(() -> customerOrderService.placeOrder(
                customer,
                placementRequest(expiredDraft.orderId(), productId, 1, simulated.orderId(), "UPI")
        ))
                .isInstanceOf(CustomerOrderPlacementException.class)
                .hasMessageContaining("expired");
        assertThat(currentStock(productId)).isEqualTo(7);

        CustomerOrderDraftResponse changedCartDraft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );
        customerOrderService.createRazorpayOrder(customer, changedCartDraft.orderId());
        String boundSimulatedId = jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'razorpayOrderId' FROM customer_order_drafts WHERE id = ?",
                String.class,
                UUID.fromString(changedCartDraft.orderId())
        );

        assertThatThrownBy(() -> customerOrderService.placeOrder(
                customer,
                placementRequest(changedCartDraft.orderId(), productId, 2, boundSimulatedId, "UPI")
        ))
                .isInstanceOf(CustomerOrderPlacementException.class)
                .hasMessageContaining("Cart changed");
        assertThat(currentStock(productId)).isEqualTo(7);
    }

    @Test
    void testCustomerSimulatedRazorpayJourneyLeavesProvidersUntouchedAndStockAtParity() {
        AuthenticatedCustomer customer = insertTestCustomer("simulated-journey");
        String productId = insertProductWithStock(4);
        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 2)))
        );

        RazorpayCreateOrderResponse first = customerOrderService.createRazorpayOrder(customer, draft.orderId());

        assertThat(first.simulated()).isTrue();
        assertThat(first.orderId()).startsWith("rzp_test_");
        assertThat(first.amount()).isEqualTo(draft.totalPaise());
        assertThat(first.currency()).isEqualTo(draft.currency());

        RazorpayCreateOrderResponse retry = customerOrderService.createRazorpayOrder(customer, draft.orderId());
        assertThat(retry).isEqualTo(first);

        String persistedRazorpayOrderId = jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'razorpayOrderId' FROM customer_order_drafts WHERE id = ?",
                String.class,
                UUID.fromString(draft.orderId())
        );
        assertThat(persistedRazorpayOrderId).isEqualTo(first.orderId());

        CustomerOrderResponse placed = customerOrderService.placeOrder(
                customer,
                placementRequest(draft.orderId(), productId, 2, first.orderId(), "CARD")
        );

        assertThat(placed.isTest()).isTrue();
        assertThat(placed.orderStatus()).isEqualTo("CONFIRMED");
        assertThat(placed.paymentStatus()).isEqualTo("CAPTURED");
        assertThat(placed.paymentMethod()).isEqualTo("CARD");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_test FROM customer_orders WHERE order_number = ?",
                Boolean.class,
                placed.orderNumber()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'razorpayOrderId' FROM customer_orders WHERE order_number = ?",
                String.class,
                placed.orderNumber()
        )).isEqualTo(first.orderId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'razorpayPaymentId' FROM customer_orders WHERE order_number = ?",
                String.class,
                placed.orderNumber()
        )).startsWith("pay_test_");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'source' FROM customer_orders WHERE order_number = ?",
                String.class,
                placed.orderNumber()
        )).isEqualTo("WEB_CHECKOUT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_number FROM customer_orders WHERE order_number = ?",
                String.class,
                placed.orderNumber()
        )).startsWith("SHRESTA-");
        assertThat(currentStock(productId)).isEqualTo(4);

        verifyNoInteractions(razorpayCheckoutService, emailNotificationService, smsDeliveryService);
    }

    @Test
    void testCustomerPaymentMethodComesFromRequestAgainstCheckConstraint() {
        AuthenticatedCustomer customer = insertTestCustomer("payment-method");
        String productId = insertProductWithStock(9);

        CustomerOrderResponse defaulted = placeThroughSimulatedLane(customer, productId, 1, null);
        assertThat(defaulted.paymentMethod()).isEqualTo("UPI");

        CustomerOrderResponse explicit = placeThroughSimulatedLane(customer, productId, 1, "NETBANKING");
        assertThat(explicit.paymentMethod()).isEqualTo("NETBANKING");

        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, 1)))
        );
        RazorpayCreateOrderResponse simulated = customerOrderService.createRazorpayOrder(customer, draft.orderId());
        assertThatThrownBy(() -> customerOrderService.placeOrder(
                customer,
                placementRequest(draft.orderId(), productId, 1, simulated.orderId(), "WALLET")
        ))
                .isInstanceOf(CustomerOrderPlacementException.class)
                .hasMessageContaining("Unsupported payment method");

        verifyNoInteractions(razorpayCheckoutService, emailNotificationService, smsDeliveryService);
        assertThat(currentStock(productId)).isEqualTo(9);
    }

    @Test
    void testCustomerRefundLifecycleIsSimulatedWithoutProviderRestockOrNotify() {
        AuthenticatedCustomer customer = insertTestCustomer("refund-lane");
        String productId = insertProductWithStock(2);
        CustomerOrderResponse placed = placeThroughSimulatedLane(customer, productId, 1, "CARD");
        String orderNumber = placed.orderNumber();

        lifecycleService.updateOrderStatusesByAdmin(
                orderNumber,
                new AdminOrderStatusUpdateRequest("PACKING", "packing", "OPS-1"),
                "CHANGE_MANAGER"
        );
        lifecycleService.updateOrderStatusesByAdmin(
                orderNumber,
                new AdminOrderStatusUpdateRequest("OUT_FOR_DELIVERY", "shipped", "OPS-2"),
                "CHANGE_MANAGER"
        );
        lifecycleService.updateOrderStatusesByAdmin(
                orderNumber,
                new AdminOrderStatusUpdateRequest("DELIVERED", "delivered", "OPS-3"),
                "CHANGE_MANAGER"
        );

        lifecycleService.requestRefundByCustomer(
                UUID.fromString(placed.customerId()),
                orderNumber,
                "damaged"
        );
        lifecycleService.approveRefundByAdmin(
                orderNumber,
                new AdminOrderRefundApprovalRequest("Approved.", "OPS-RFND"),
                "CHANGE_MANAGER"
        );
        AdminOrderRefundStatusResponse sync = lifecycleService.checkAndSyncRefundStatusByAdmin(orderNumber, "CHANGE_MANAGER");

        assertThat(sync.refundSuccessful()).isTrue();
        assertThat(sync.paymentMarkedRefunded()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_status FROM customer_orders WHERE order_number = ?",
                String.class,
                orderNumber
        )).isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT to_status
                FROM customer_order_status_events
                WHERE order_id = (SELECT id FROM customer_orders WHERE order_number = ?)
                  AND event_type = 'PAYMENT_STATUS'
                ORDER BY created_at, id
                """, String.class, orderNumber))
                .containsSubsequence("REFUND_REQUESTED", "REFUND_INITIATED", "REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_order_status_events WHERE note LIKE '%[refund-id: rfnd_test_%'",
                Integer.class
        )).isGreaterThanOrEqualTo(1);
        assertThat(currentStock(productId)).isEqualTo(2);

        verifyNoInteractions(razorpayCheckoutService, emailNotificationService, smsDeliveryService);
    }

    @Test
    void webhookAgainstTestOrderIsInertAndSimulatedIdLookupThrowsCleanly() {
        AuthenticatedCustomer customer = insertTestCustomer("webhook-lane");
        String productId = insertProductWithStock(6);
        CustomerOrderResponse placed = placeThroughSimulatedLane(customer, productId, 1, "CARD");

        CustomerOrderLifecycleService.PaymentWebhookOrderUpdateResult captured = lifecycleService.applyRazorpayPaymentWebhook(
                placed.orderNumber(), "payment.captured", "pay_test_ignored", "evt_captured_ignored");
        CustomerOrderLifecycleService.PaymentWebhookOrderUpdateResult failed = lifecycleService.applyRazorpayPaymentWebhook(
                placed.orderNumber(), "payment.failed", "pay_test_ignored", "evt_failed_ignored");

        assertThat(captured.changed()).isFalse();
        assertThat(failed.changed()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_status FROM customer_orders WHERE order_number = ?",
                String.class,
                placed.orderNumber()
        )).isEqualTo("CAPTURED");
        assertThat(currentStock(productId)).isEqualTo(6);

        CustomerOrderLifecycleService.PaymentWebhookOrderUpdateResult refunded = lifecycleService.applyRazorpayPaymentWebhook(
                placed.orderNumber(), "payment.refunded", "pay_test_ignored", "evt_refunded_ignored");

        assertThat(refunded.changed()).isTrue();
        assertThat(refunded.paymentStatus()).isEqualTo("REFUNDED");
        assertThat(currentStock(productId)).isEqualTo(6);
        verifyNoInteractions(razorpayCheckoutService, emailNotificationService, smsDeliveryService);

        assertThatThrownBy(() -> lifecycleService.applyRazorpayPaymentWebhook(
                "RZP_TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT).substring(0, 8),
                "payment.captured",
                "pay_test_unknown",
                "evt_unknown"
        )).isInstanceOf(CustomerOrderNotFoundException.class);
    }

    @Test
    void adminSurfacesFlagTestOrdersAndExcludeTestCustomersFromRollups() {
        AuthenticatedCustomer testCustomer = insertTestCustomer("admin-surfaces");
        String productId = insertProductWithStock(8);
        CustomerOrderResponse placed = placeThroughSimulatedLane(testCustomer, productId, 1, "CARD");

        UUID normalCustomerId = seededCustomerId();
        String normalOrderNumber = "SHRESTA-20260809-NRMLTST1";
        insertOrder(normalOrderNumber, normalCustomerId, false);

        AdminOrderSummaryResponse testSummary = findAdminSummary(placed.orderNumber());
        AdminOrderSummaryResponse normalSummary = findAdminSummary(normalOrderNumber);

        assertThat(testSummary.isTest()).isTrue();
        assertThat(normalSummary.isTest()).isFalse();

        List<AdminCustomerOrderSummaryResponse> rollups = lifecycleService.listCustomerSummariesForAdmin(200, 0);
        assertThat(rollups).noneMatch(row -> row.customerId().equals(testCustomer.customerId().toString()));
        assertThat(rollups).anyMatch(row -> row.customerId().equals(normalCustomerId.toString()));

        CustomerOrderResponse detail = lifecycleService.findOrderForAdmin(placed.orderNumber());
        assertThat(detail.isTest()).isTrue();
        assertThat(lifecycleService.findOrderForAdmin(normalOrderNumber).isTest()).isFalse();
    }

    private CustomerOrderResponse placeThroughSimulatedLane(
            AuthenticatedCustomer customer,
            String productId,
            int quantity,
            String paymentMethod
    ) {
        CustomerOrderDraftResponse draft = customerOrderService.createOrReuseDraft(
                customer,
                new CustomerOrderDraftRequest(List.of(new CustomerOrderDraftRequest.LineItem(productId, quantity)))
        );
        RazorpayCreateOrderResponse simulated = customerOrderService.createRazorpayOrder(customer, draft.orderId());
        return customerOrderService.placeOrder(
                customer,
                placementRequest(draft.orderId(), productId, quantity, simulated.orderId(), paymentMethod)
        );
    }

    private CustomerOrderPlacementRequest placementRequest(
            String draftOrderId,
            String productId,
            int quantity,
            String razorpayOrderId,
            String paymentMethod
    ) {
        return new CustomerOrderPlacementRequest(
                List.of(new CustomerOrderPlacementRequest.LineItem(productId, quantity)),
                draftOrderId,
                new CustomerOrderPlacementRequest.RazorpayPaymentProof(
                        razorpayOrderId,
                        "pay_test_" + UUID.randomUUID(),
                        ""
                ),
                new CustomerOrderPlacementRequest.Contact("integration-test@example.com", "9876543210"),
                new CustomerOrderPlacementRequest.ShippingAddress(
                        "Test User", "9876543210", "1 Test Road", null, null,
                        "Bengaluru", "Karnataka", "560001", "India", "HOME"),
                "STANDARD",
                paymentMethod,
                true
        );
    }

    private AdminOrderSummaryResponse findAdminSummary(String orderNumber) {
        return lifecycleService.listOrdersForAdmin(200, 0, "", "").stream()
                .filter(row -> row.orderNumber().equals(orderNumber))
                .findFirst()
                .orElseThrow(() -> new AssertionError("admin order summary missing: " + orderNumber));
    }

    private AuthenticatedCustomer insertTestCustomer(String tag) {
        UUID customerId = UUID.randomUUID();
        String email = tag + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO customer_accounts (id, primary_email, display_name, status, is_test, metadata, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', TRUE, '{}'::jsonb, now(), now())",
                customerId,
                email.toLowerCase(Locale.ROOT),
                "TEST Lane " + tag
        );
        return new AuthenticatedCustomer(
                customerId,
                email,
                "TEST Lane " + tag,
                "ACTIVE",
                Instant.now().plus(1, ChronoUnit.HOURS),
                true
        );
    }

    private UUID seededCustomerId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customer_accounts WHERE primary_email = 'testuser@gmail.com' LIMIT 1",
                UUID.class
        );
    }

    private void backdateDraftExpiry(String draftOrderId) {
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        jdbcTemplate.update(
                "UPDATE customer_order_drafts SET created_at = ?, expires_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(past),
                Timestamp.from(past.plus(2, ChronoUnit.MINUTES)),
                Timestamp.from(past.plus(2, ChronoUnit.MINUTES)),
                UUID.fromString(draftOrderId)
        );
    }

    private void insertOrder(String orderNumber, UUID customerId, boolean isTest) {
        jdbcTemplate.update("""
                INSERT INTO customer_orders (
                    id, order_number, customer_id, customer_email, status, payment_status, fulfillment_status,
                    currency, subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise,
                    delivery_mode, payment_method, contact_snapshot, shipping_address_snapshot, metadata, is_test,
                    placed_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, 'CONFIRMED', 'CAPTURED', 'PENDING',
                    'INR', 10000, 0, 0, 0, 10000,
                    'STANDARD', 'CARD',
                    CAST(? AS jsonb), CAST(? AS jsonb), '{}'::jsonb, ?,
                    now(), now(), now()
                )
                """,
                UUID.randomUUID(),
                orderNumber,
                customerId,
                "testuser@gmail.com",
                "{\"email\":\"testuser@gmail.com\",\"phone\":\"9876543210\"}",
                "{\"fullName\":\"Test User\",\"phone\":\"9876543210\",\"addressLine1\":\"Line 1\",\"addressLine2\":\"\",\"landmark\":\"\",\"city\":\"Bengaluru\",\"state\":\"Karnataka\",\"postalCode\":\"560001\",\"country\":\"India\",\"addressType\":\"HOME\"}",
                isTest
        );
    }

    private String insertProductWithStock(int stockQuantity) {
        UUID sectionId = UUID.randomUUID();
        String sectionKey = "test_lane_section_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
        String productId = "test-lane-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

        jdbcTemplate.update(
                """
                INSERT INTO storefront_home_sections (
                    id, section_key, section_type, eyebrow, title, description, sort_order, metadata, is_active, created_at, updated_at
                ) VALUES (
                    ?, ?, 'product_grid', 'test', 'Test Section', 'Test lane section', 0, '{}'::jsonb, TRUE, now(), now()
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
}

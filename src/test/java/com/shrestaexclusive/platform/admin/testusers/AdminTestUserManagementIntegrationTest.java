package com.shrestaexclusive.platform.admin.testusers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestCreateRequest;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestDecisionRequest;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestResponse;
import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestService;
import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminAccessGuard;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "shresta.admin.api-key=local-shresta-admin-key",
                "shresta.kv.enabled=false",
                "shresta.mutation.idempotency.enabled=false",
                "shresta.mutation.locking.enabled=false",
                "shresta.scheduling.enabled=false",
                "shresta.media.asset-base-url=http://localhost:9010/shresta-local-assets"
        }
)
class AdminTestUserManagementIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shresta")
            .withUsername("shresta_app")
            .withPassword("change-me");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AdminChangeRequestService changeRequestService;

    @Test
    void rejectsCreateWhenIdentityBelongsToNonTestAccount() {
        String email = uniqueEmail("inuse");
        insertNormalAccount(email, "9845012345");

        assertThatThrownBy(() -> submitCreate(email, "QA User", "9845012346", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("identity already in use");
    }

    @Test
    void rejectsCreateWithMalformedMobile() {
        String email = uniqueEmail("badmobile");

        assertThatThrownBy(() -> submitCreate(email, "QA User", "12345", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mobile must be a valid Indian 10-digit number");
    }

    @Test
    void rejectsCreateWhenEntityKeyDiffersFromNormalizedEmail() {
        assertThatThrownBy(() -> changeRequestService.create(
                "CHANGE_MANAGER",
                "manager@example.com",
                new AdminChangeRequestCreateRequest(
                        "test-user-management", "customer_accounts", uniqueEmail("entitykey"), "CREATE",
                        "manager@example.com",
                        Map.of("displayName", "QA User", "email", uniqueEmail("entitykey")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entityKey must equal the lowercase email");
    }

    @Test
    void rejectsDeleteWithoutReason() {
        UUID customerId = insertTestAccount(uniqueEmail("noreason"));

        assertThatThrownBy(() -> changeRequestService.createOrUpdatePending(
                "CHANGE_MANAGER",
                "manager@example.com",
                new AdminChangeRequestCreateRequest(
                        "test-user-management", "customer_accounts", customerId.toString(), "DELETE",
                        "manager@example.com", Map.of("customerId", customerId.toString()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason is required");
    }

    @Test
    void rejectsDeleteOfNonTestAccount() {
        UUID customerId = insertNormalAccount(uniqueEmail("nontest"), null);

        assertThatThrownBy(() -> submitDelete(customerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("test account not found for customerId " + customerId);
    }

    @Test
    void submitFailureSurfacesAsStandardBadRequestEnvelope() throws Exception {
        String email = uniqueEmail("httpinuse");
        insertNormalAccount(email, null);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/change-requests",
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "requestType", "test-user-management",
                        "entityType", "customer_accounts",
                        "entityKey", email,
                        "action", "CREATE",
                        "submittedBy", "manager@example.com",
                        "payload", Map.of("displayName", "QA User", "email", email))),
                        mutationHeaders("CHANGE_MANAGER")),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("INVALID_ADMIN_CHANGE_REQUEST");
        assertThat(error.path("message").asText()).isEqualTo("identity already in use");
    }

    @Test
    void approvingCreateMintsActiveTestAccountWithHashedOtpOnly() throws Exception {
        String email = uniqueEmail("mint");
        String mobile = uniqueMobile("98450");
        AdminChangeRequestResponse pending = submitCreate(email, "Minted User", mobile, "first lane account");

        AdminChangeRequestResponse approved = changeRequestService.approve(
                pending.requestKey(), "CHANGE_APPROVER",
                new AdminChangeRequestDecisionRequest("approver@example.com", "looks fine"));

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.payload()).containsOnlyKeys("displayName", "email", "mobile", "note");

        UUID customerId = jdbc.sql("""
                        SELECT id FROM customer_accounts
                        WHERE primary_email = :email AND status = 'ACTIVE' AND is_test = TRUE
                          AND metadata->>'source' = 'admin-test-user'
                        """)
                .param("email", email)
                .query(UUID.class)
                .single();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer_auth_identities
                        WHERE customer_id = :id AND is_verified = TRUE AND last_verified_at IS NOT NULL
                          AND identity_value IN (:email, :mobile)
                        """)
                .param("id", customerId).param("email", email).param("mobile", mobile)
                .query(Long.class).single()).isEqualTo(2);

        JsonNode storedPayload = readPayload(pending.requestKey());
        assertThat(storedPayload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("displayName", "email", "mobile", "note");

        Map<String, Object> testRow = jdbc.sql("""
                        SELECT otp_hash, pending_otp, note, created_by_admin, is_active, otp_revealed_at
                        FROM storefront_test_accounts WHERE customer_id = :id
                        """)
                .param("id", customerId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "otp_hash", rs.getString("otp_hash"),
                        "pending_otp", rs.getString("pending_otp"),
                        "note", rs.getString("note"),
                        "created_by_admin", rs.getString("created_by_admin"),
                        "is_active", rs.getBoolean("is_active"),
                        "otp_revealed_at", rs.getTimestamp("otp_revealed_at") == null
                                ? "" : rs.getTimestamp("otp_revealed_at").toString()))
                .single();
        assertThat((String) testRow.get("otp_hash")).matches("^[0-9a-f]{64}$");
        assertThat((String) testRow.get("pending_otp")).matches("^\\d{6}$");
        assertThat(testRow.get("note")).isEqualTo("first lane account");
        assertThat(testRow.get("created_by_admin")).isEqualTo("approver@example.com");
        assertThat(testRow.get("is_active")).isEqualTo(Boolean.TRUE);
        assertThat(testRow.get("otp_revealed_at")).isEqualTo("");
    }

    @Test
    void approvingCreateAdoptsSuspendedJunkAccountInPlace() {
        String email = uniqueEmail("adopt");
        String mobile = uniqueMobile("97401");
        UUID junkId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_accounts (id, primary_email, display_name, status, metadata)
                        VALUES (:id, :email, 'Shresta Customer', 'SUSPENDED', '{"source":"identity-otp"}'::jsonb)
                        """)
                .param("id", junkId).param("email", email).update();
        jdbc.sql("""
                        INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value, is_verified, metadata)
                        VALUES (:id, 'EMAIL', :email, FALSE, '{"source":"identity-otp"}'::jsonb)
                        """)
                .param("id", junkId).param("email", email).update();

        AdminChangeRequestResponse pending = submitCreate(email, "Adopted User", mobile, null);
        AdminChangeRequestResponse approved = changeRequestService.approve(
                pending.requestKey(), "CHANGE_APPROVER",
                new AdminChangeRequestDecisionRequest("approver@example.com", "adopt"));

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM customer_accounts WHERE primary_email = :email")
                .param("email", email).query(Long.class).single()).isEqualTo(1);
        Map<String, Object> account = jdbc.sql("""
                        SELECT status, is_test, display_name FROM customer_accounts WHERE id = :id
                        """)
                .param("id", junkId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "is_test", rs.getBoolean("is_test"),
                        "display_name", rs.getString("display_name")))
                .single();
        assertThat(account).containsEntry("status", "ACTIVE")
                .containsEntry("is_test", Boolean.TRUE)
                .containsEntry("display_name", "Adopted User");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer_auth_identities
                        WHERE customer_id = :id AND is_verified = TRUE AND identity_value IN (:email, :mobile)
                        """)
                .param("id", junkId).param("email", email).param("mobile", mobile)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM storefront_test_accounts WHERE customer_id = :id AND pending_otp IS NOT NULL")
                .param("id", junkId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void approvalFailureLeavesRequestActionableWhenIdentityWasClaimedAfterSubmit() {
        String email = uniqueEmail("race");
        AdminChangeRequestResponse pending = submitCreate(email, "Raced User", null, null);
        insertNormalAccount(email, null);

        assertThatThrownBy(() -> changeRequestService.approve(
                pending.requestKey(), "CHANGE_APPROVER",
                new AdminChangeRequestDecisionRequest("approver@example.com", "go")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("identity already in use");

        assertThat(changeRequestService.get(pending.requestKey()).status()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void approvingDeletePurgesTheWholeAccountGraph() {
        String email = uniqueEmail("purge");
        UUID customerId = insertTestAccount(email);
        UUID orderId = insertOrder(customerId, email);
        UUID draftId = insertDraft(customerId, email);
        jdbc.sql("""
                        INSERT INTO customer_sessions (customer_id, session_token_hash, status, expires_at)
                        VALUES (:id, :tokenHash, 'ACTIVE', now() + interval '12 hours')
                        """)
                .param("id", customerId)
                .param("tokenHash", "hash-" + UUID.randomUUID().toString().replace("-", ""))
                .update();

        AdminChangeRequestResponse pending = submitDelete(customerId);
        AdminChangeRequestResponse approved = changeRequestService.approve(
                pending.requestKey(), "CHANGE_APPROVER",
                new AdminChangeRequestDecisionRequest("approver@example.com", "cleanup"));

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM customer_order_items WHERE order_id = :id")
                .param("id", orderId).query(Long.class).single()).isZero();
        assertThat(countFor("customer_orders", customerId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM customer_order_draft_items WHERE order_draft_id = :id")
                .param("id", draftId).query(Long.class).single()).isZero();
        assertThat(countFor("customer_order_drafts", customerId)).isZero();
        assertThat(countFor("customer_sessions", customerId)).isZero();
        assertThat(countFor("customer_auth_identities", customerId)).isZero();
        assertThat(countFor("storefront_test_accounts", customerId)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM customer_accounts WHERE id = :id")
                .param("id", customerId).query(Long.class).single()).isZero();
    }

    @Test
    void listRevealAndRoleGateFollowTheFrontendContract() throws Exception {
        ResponseEntity<String> unauthorized = restTemplate.getForEntity("/api/v1/admin/test-users", String.class);
        assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
        assertThat(objectMapper.readTree(unauthorized.getBody()).path("error").path("code").asText())
                .isEqualTo("ADMIN_UNAUTHORIZED");

        String email = uniqueEmail("e2e");
        String mobile = uniqueMobile("90080");
        AdminChangeRequestResponse pending = submitCreate(email, "E2E User", mobile, "oeuvres");
        changeRequestService.approve(pending.requestKey(), "CHANGE_APPROVER",
                new AdminChangeRequestDecisionRequest("approver@example.com", "ok"));
        UUID customerId = jdbc.sql("SELECT id FROM customer_accounts WHERE primary_email = :email")
                .param("email", email).query(UUID.class).single();
        insertOrder(customerId, email);

        ResponseEntity<String> listed = restTemplate.exchange(
                "/api/v1/admin/test-users?page=-3&size=500",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders("CHANGE_MANAGER")),
                String.class);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(listed.getHeaders().getFirst("Cache-Control")).contains("no-store");
        JsonNode page = objectMapper.readTree(listed.getBody()).path("data");
        assertThat(page.path("page").asInt()).isEqualTo(0);
        assertThat(page.path("size").asInt()).isEqualTo(100);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode item = null;
        for (JsonNode candidate : page.path("items")) {
            if (customerId.toString().equals(candidate.path("customerId").asText())) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("displayName").asText()).isEqualTo("E2E User");
        assertThat(item.path("email").asText()).isEqualTo(email);
        assertThat(item.path("mobile").asText()).isEqualTo(mobile);
        assertThat(item.path("note").asText()).isEqualTo("oeuvres");
        assertThat(item.path("active").asBoolean()).isTrue();
        assertThat(item.path("testOrdersCount").asLong()).isEqualTo(1);
        assertThat(item.path("createdAt").asText()).isNotBlank();
        assertThat(item.path("otpRevealed").asBoolean()).isFalse();
        assertThat(item.has("otp")).isFalse();
        assertThat(item.has("pendingOtp")).isFalse();

        ResponseEntity<String> revealed = restTemplate.exchange(
                "/api/v1/admin/test-users/" + customerId + "/reveal-otp",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders("CHANGE_MANAGER")),
                String.class);
        assertThat(revealed.getStatusCode().value()).isEqualTo(200);
        assertThat(revealed.getHeaders().getFirst("Cache-Control")).contains("no-store");
        String otp = objectMapper.readTree(revealed.getBody()).path("data").path("otp").asText();
        assertThat(otp).matches("^\\d{6}$");
        Map<String, Object> afterReveal = jdbc.sql("""
                        SELECT pending_otp, otp_revealed_at, otp_hash
                        FROM storefront_test_accounts WHERE customer_id = :id
                        """)
                .param("id", customerId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "pending_otp", rs.getString("pending_otp") == null ? "" : rs.getString("pending_otp"),
                        "otp_revealed_at", rs.getTimestamp("otp_revealed_at") == null ? "" : "set",
                        "otp_hash", rs.getString("otp_hash")))
                .single();
        assertThat(afterReveal).containsEntry("pending_otp", "").containsEntry("otp_revealed_at", "set");
        assertThat(afterReveal.get("otp_hash")).isEqualTo(sha256Hex(otp));

        ResponseEntity<String> revealedAgain = restTemplate.exchange(
                "/api/v1/admin/test-users/" + customerId + "/reveal-otp",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders("CHANGE_MANAGER")),
                String.class);
        assertThat(revealedAgain.getStatusCode().value()).isEqualTo(409);
        assertThat(objectMapper.readTree(revealedAgain.getBody()).path("error").path("code").asText())
                .isEqualTo("TEST_USER_OTP_ALREADY_REVEALED");

        ResponseEntity<String> revealUnknown = restTemplate.exchange(
                "/api/v1/admin/test-users/" + UUID.randomUUID() + "/reveal-otp",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders("CHANGE_MANAGER")),
                String.class);
        assertThat(revealUnknown.getStatusCode().value()).isEqualTo(404);
        assertThat(objectMapper.readTree(revealUnknown.getBody()).path("error").path("code").asText())
                .isEqualTo("TEST_USER_NOT_FOUND");

        ResponseEntity<String> listedAfter = restTemplate.exchange(
                "/api/v1/admin/test-users?page=0&size=50",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders("CHANGE_MANAGER")),
                String.class);
        JsonNode itemsAfter = objectMapper.readTree(listedAfter.getBody()).path("data").path("items");
        boolean flipped = false;
        for (JsonNode candidate : itemsAfter) {
            if (customerId.toString().equals(candidate.path("customerId").asText())) {
                flipped = candidate.path("otpRevealed").asBoolean();
            }
        }
        assertThat(flipped).isTrue();
    }

    private AdminChangeRequestResponse submitCreate(String email, String displayName, String mobile, String note) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("displayName", displayName);
        payload.put("email", email);
        if (mobile != null) {
            payload.put("mobile", mobile);
        }
        if (note != null) {
            payload.put("note", note);
        }
        return changeRequestService.create(
                "CHANGE_MANAGER",
                "manager@example.com",
                new AdminChangeRequestCreateRequest(
                        "test-user-management", "customer_accounts", email, "CREATE",
                        "manager@example.com", payload));
    }

    private AdminChangeRequestResponse submitDelete(UUID customerId) {
        return changeRequestService.createOrUpdatePending(
                "CHANGE_MANAGER",
                "manager@example.com",
                new AdminChangeRequestCreateRequest(
                        "test-user-management", "customer_accounts", customerId.toString(), "DELETE",
                        "manager@example.com",
                        Map.of("customerId", customerId.toString(), "reason", "lane cleanup")));
    }

    private UUID insertNormalAccount(String email, String mobile) {
        UUID customerId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_accounts (id, primary_email, display_name, status, metadata)
                        VALUES (:id, :email, 'Regular User', 'ACTIVE', '{"source":"registration"}'::jsonb)
                        """)
                .param("id", customerId).param("email", email).update();
        jdbc.sql("""
                        INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value, is_verified)
                        VALUES (:id, 'EMAIL', :email, TRUE)
                        """)
                .param("id", customerId).param("email", email).update();
        if (mobile != null) {
            jdbc.sql("""
                            INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value, is_verified)
                            VALUES (:id, 'MOBILE', :mobile, TRUE)
                            """)
                    .param("id", customerId).param("mobile", mobile).update();
        }
        return customerId;
    }

    private UUID insertTestAccount(String email) {
        UUID customerId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_accounts (id, primary_email, display_name, status, is_test, metadata)
                        VALUES (:id, :email, 'TEST User', 'ACTIVE', TRUE, '{"source":"admin-test-user"}'::jsonb)
                        """)
                .param("id", customerId).param("email", email).update();
        jdbc.sql("""
                        INSERT INTO customer_auth_identities (customer_id, identity_type, identity_value, is_verified)
                        VALUES (:id, 'EMAIL', :email, TRUE)
                        """)
                .param("id", customerId).param("email", email).update();
        jdbc.sql("""
                        INSERT INTO storefront_test_accounts (customer_id, otp_hash, pending_otp, created_by_admin, is_active)
                        VALUES (:id, :otpHash, :otp, 'platform-test-suite', TRUE)
                        """)
                .param("id", customerId)
                .param("otpHash", sha256Hex("87654321"))
                .param("otp", "87654321")
                .update();
        return customerId;
    }

    private UUID insertOrder(UUID customerId, String email) {
        UUID orderId = jdbc.sql("""
                        INSERT INTO customer_orders (
                            customer_id, order_number, customer_email, status, payment_status, fulfillment_status,
                            subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise,
                            delivery_mode, payment_method, contact_snapshot, shipping_address_snapshot, is_test
                        ) VALUES (
                            :id, :orderNumber, :email, 'PLACED', 'PENDING', 'PENDING',
                            0, 0, 0, 0, 0,
                            'STANDARD', 'UPI', '{}'::jsonb, '{}'::jsonb, TRUE
                        )
                        RETURNING id
                        """)
                .param("id", customerId)
                .param("orderNumber", "SHRESTA-20260101-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase())
                .param("email", email)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO customer_order_items (
                            order_id, product_item_key, product_sku, product_slug, product_name,
                            family_key, product_type, quantity, unit_price_paise, line_total_paise
                        ) VALUES (
                            :orderId, 'item-1', 'SKU-1', 'slug-1', 'Sample', 'silk_saree', 'saree', 1, 0, 0
                        )
                        """)
                .param("orderId", orderId).update();
        return orderId;
    }

    private UUID insertDraft(UUID customerId, String email) {
        UUID draftId = jdbc.sql("""
                        INSERT INTO customer_order_drafts (
                            customer_id, draft_number, customer_email, status, cart_signature,
                            subtotal_paise, delivery_paise, discount_paise, tax_paise, total_paise, expires_at
                        ) VALUES (
                            :id, :draftNumber, :email, 'ACTIVE', :signature,
                            0, 0, 0, 0, 0, now() + interval '1 day'
                        )
                        RETURNING id
                        """)
                .param("id", customerId)
                .param("draftNumber", "SHRESTA-DRAFT-20260101-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase())
                .param("email", email)
                .param("signature", "sig-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO customer_order_draft_items (
                            order_draft_id, product_item_key, product_sku, product_slug, product_name,
                            family_key, product_type, quantity, unit_price_paise, line_total_paise
                        ) VALUES (
                            :draftId, 'item-1', 'SKU-1', 'slug-1', 'Sample', 'silk_saree', 'saree', 1, 0, 0
                        )
                        """)
                .param("draftId", draftId).update();
        return draftId;
    }

    private JsonNode readPayload(String requestKey) throws Exception {
        String json = jdbc.sql("SELECT payload::text FROM admin_change_requests WHERE request_key = :key")
                .param("key", requestKey)
                .query(String.class)
                .single();
        return objectMapper.readTree(json);
    }

    private long countFor(String table, UUID customerId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE customer_id = :id")
                .param("id", customerId)
                .query(Long.class)
                .single();
    }

    private HttpHeaders adminHeaders(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(StorefrontAdminAccessGuard.ADMIN_KEY_HEADER, "local-shresta-admin-key");
        headers.set(StorefrontAdminAccessGuard.ADMIN_ROLE_HEADER, role);
        return headers;
    }

    private HttpHeaders mutationHeaders(String role) {
        HttpHeaders headers = adminHeaders(role);
        headers.set("Idempotency-Key", "itest-" + UUID.randomUUID());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String uniqueMobile(String prefix) {
        return prefix + (10000 + Math.floorMod(System.nanoTime(), 90000));
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com";
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

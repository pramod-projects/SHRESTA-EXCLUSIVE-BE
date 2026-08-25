package com.shrestaexclusive.platform.admin.testusers;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shrestaexclusive.platform.admin.changes.AdminChangeRequestResponse;

@Service
public class AdminTestUserService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9][0-9]{9}$");
    private static final String JUNK_SOURCE = "identity-otp";
    private static final String TEST_USER_SOURCE = "admin-test-user";
    private static final String DRAFT_EXPIRY_REASON = "ACCOUNT_DELETED";

    private final JdbcClient jdbcClient;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminTestUserService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void validateCreateSubmit(String entityKey, Map<String, Object> payload) {
        String displayName = text(payload, "displayName");
        if (displayName == null) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (displayName.length() > 160) {
            throw new IllegalArgumentException("displayName must be at most 160 characters");
        }
        String rawEmail = text(payload, "email");
        if (rawEmail == null) {
            throw new IllegalArgumentException("email is required");
        }
        String email = normalizeEmail(rawEmail);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("email is not a valid email address");
        }
        if (entityKey == null || !email.equals(entityKey.trim())) {
            throw new IllegalArgumentException("entityKey must equal the lowercase email");
        }
        String rawMobile = text(payload, "mobile");
        String mobile = normalizeMobile(rawMobile);
        if (rawMobile != null && mobile == null) {
            throw new IllegalArgumentException("mobile must be a valid Indian 10-digit number");
        }
        String note = text(payload, "note");
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("note must be at most 500 characters");
        }
        assertCreatableIdentities(email, mobile);
    }

    public void validateDeleteSubmit(String entityKey, Map<String, Object> payload) {
        if (text(payload, "reason") == null) {
            throw new IllegalArgumentException("reason is required");
        }
        UUID customerId = parseEntityCustomerId(entityKey, payload);
        boolean exists = jdbcClient.sql("""
                        SELECT 1 FROM customer_accounts
                        WHERE id = :id AND status <> 'DELETED' AND is_test = TRUE
                        LIMIT 1
                        """)
                .param("id", customerId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("test account not found for customerId " + customerId);
        }
    }

    public void applyCreate(AdminChangeRequestResponse request, String reviewedBy) {
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        String displayName = text(payload, "displayName");
        String email = normalizeEmail(text(payload, "email"));
        String mobile = normalizeMobile(text(payload, "mobile"));
        String note = text(payload, "note");
        if (displayName == null || email == null) {
            throw new IllegalStateException("test user payload is missing displayName or email");
        }

        IdentityOwner adoptable = findAdoptableOwner(email, mobile);
        UUID customerId = adoptable == null
                ? insertFreshAccount(email, displayName, mobile)
                : convertJunkAccount(adoptable.id(), email, displayName, mobile);
        mintAndStoreOtp(customerId, note, reviewedBy);
    }

    public void applyDelete(AdminChangeRequestResponse request) {
        UUID customerId = parseUuid(request.entityKey());
        AccountState account = jdbcClient.sql("""
                        SELECT id, status, is_test FROM customer_accounts WHERE id = :id LIMIT 1
                        """)
                .param("id", customerId)
                .query((rs, rowNum) -> new AccountState(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("is_test")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("test account not found for customerId " + customerId));
        if ("DELETED".equals(account.status()) || !account.isTest()) {
            throw new IllegalStateException("refusing to delete a non-test account: " + customerId);
        }

        jdbcClient.sql("""
                        UPDATE customer_order_drafts
                        SET status = 'EXPIRED', invalidated_at = now(), invalidation_reason = :reason, updated_at = now()
                        WHERE customer_id = :id AND status = 'ACTIVE'
                        """)
                .param("reason", DRAFT_EXPIRY_REASON)
                .param("id", customerId)
                .update();
        jdbcClient.sql("DELETE FROM customer_order_drafts WHERE customer_id = :id")
                .param("id", customerId)
                .update();
        jdbcClient.sql("DELETE FROM customer_orders WHERE customer_id = :id")
                .param("id", customerId)
                .update();
        jdbcClient.sql("DELETE FROM storefront_test_accounts WHERE customer_id = :id")
                .param("id", customerId)
                .update();
        jdbcClient.sql("DELETE FROM customer_accounts WHERE id = :id")
                .param("id", customerId)
                .update();
    }

    @Transactional(readOnly = true)
    public AdminTestUserListResponse list(int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(Math.max(1, size), 100);
        List<AdminTestUserItem> items = jdbcClient.sql("""
                        SELECT account.id AS customer_id,
                               account.display_name,
                               COALESCE(email_identity.identity_value, account.primary_email) AS email,
                               mobile_identity.identity_value AS mobile,
                               test_account.note,
                               test_account.is_active,
                               test_account.created_at,
                               test_account.otp_revealed_at,
                               (SELECT count(*) FROM customer_orders customer_order
                                WHERE customer_order.customer_id = account.id AND customer_order.is_test = TRUE) AS test_orders_count
                        FROM storefront_test_accounts test_account
                        JOIN customer_accounts account ON account.id = test_account.customer_id
                        LEFT JOIN LATERAL (
                            SELECT identity.identity_value
                            FROM customer_auth_identities identity
                            WHERE identity.customer_id = account.id AND identity.identity_type = 'EMAIL'
                            ORDER BY identity.created_at
                            LIMIT 1
                        ) email_identity ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT identity.identity_value
                            FROM customer_auth_identities identity
                            WHERE identity.customer_id = account.id AND identity.identity_type = 'MOBILE'
                            ORDER BY identity.created_at
                            LIMIT 1
                        ) mobile_identity ON TRUE
                        WHERE account.is_test = TRUE AND account.status <> 'DELETED'
                        ORDER BY test_account.created_at DESC, account.id
                        LIMIT :size OFFSET :offset
                        """)
                .param("size", normalizedSize)
                .param("offset", normalizedPage * normalizedSize)
                .query((rs, rowNum) -> new AdminTestUserItem(
                        rs.getObject("customer_id", UUID.class).toString(),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("mobile"),
                        rs.getString("note"),
                        rs.getBoolean("is_active"),
                        rs.getLong("test_orders_count"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("otp_revealed_at") != null))
                .list();
        long total = jdbcClient.sql("""
                        SELECT count(*)
                        FROM storefront_test_accounts test_account
                        JOIN customer_accounts account ON account.id = test_account.customer_id
                        WHERE account.is_test = TRUE AND account.status <> 'DELETED'
                        """)
                .query(Long.class)
                .single();
        return new AdminTestUserListResponse(items, normalizedPage, normalizedSize, total);
    }

    @Transactional
    public AdminTestUserOtpRevealResponse revealOtp(String rawCustomerId) {
        UUID customerId;
        try {
            customerId = UUID.fromString(rawCustomerId == null ? "" : rawCustomerId.trim());
        } catch (IllegalArgumentException exception) {
            throw new TestUserNotFoundException(String.valueOf(rawCustomerId));
        }
        PendingOtp pending = jdbcClient.sql("""
                        SELECT pending_otp, otp_revealed_at
                        FROM storefront_test_accounts
                        WHERE customer_id = :id
                        FOR UPDATE
                        """)
                .param("id", customerId)
                .query((rs, rowNum) -> new PendingOtp(
                        rs.getString("pending_otp"),
                        rs.getTimestamp("otp_revealed_at") == null ? null : rs.getTimestamp("otp_revealed_at").toInstant()))
                .optional()
                .orElseThrow(() -> new TestUserNotFoundException(customerId.toString()));
        if (pending.otpRevealedAt() != null || pending.pendingOtp() == null) {
            throw new TestUserOtpAlreadyRevealedException(customerId.toString());
        }
        int consumed = jdbcClient.sql("""
                        UPDATE storefront_test_accounts
                        SET otp_revealed_at = now(), pending_otp = NULL, updated_at = now()
                        WHERE customer_id = :id AND otp_revealed_at IS NULL AND pending_otp IS NOT NULL
                        """)
                .param("id", customerId)
                .update();
        if (consumed != 1) {
            throw new TestUserOtpAlreadyRevealedException(customerId.toString());
        }
        return new AdminTestUserOtpRevealResponse(pending.pendingOtp());
    }

    private void assertCreatableIdentities(String email, String mobile) {
        for (IdentityOwner owner : identityOwners(email, mobile)) {
            if (owner.isTest()) {
                throw new IllegalArgumentException("test user already exists");
            }
            if (!isAdoptableJunk(owner)) {
                throw new IllegalArgumentException("identity already in use");
            }
        }
    }

    private IdentityOwner findAdoptableOwner(String email, String mobile) {
        List<IdentityOwner> owners = identityOwners(email, mobile);
        for (IdentityOwner owner : owners) {
            if (owner.isTest()) {
                throw new IllegalStateException("test user already exists");
            }
            if (!isAdoptableJunk(owner)) {
                throw new IllegalStateException("identity already in use");
            }
        }
        if (owners.size() > 1) {
            throw new IllegalStateException("identity already in use");
        }
        return owners.isEmpty() ? null : owners.get(0);
    }

    private boolean isAdoptableJunk(IdentityOwner owner) {
        return "SUSPENDED".equals(owner.status())
                && JUNK_SOURCE.equals(owner.source())
                && countOrdersAndDrafts(owner.id()) == 0;
    }

    private long countOrdersAndDrafts(UUID customerId) {
        Long orders = jdbcClient.sql("SELECT count(*) FROM customer_orders WHERE customer_id = :id")
                .param("id", customerId)
                .query(Long.class)
                .single();
        Long drafts = jdbcClient.sql("SELECT count(*) FROM customer_order_drafts WHERE customer_id = :id")
                .param("id", customerId)
                .query(Long.class)
                .single();
        return orders + drafts;
    }

    private List<IdentityOwner> identityOwners(String email, String mobile) {
        List<String> values = new ArrayList<>();
        values.add(email);
        if (mobile != null) {
            values.add(mobile);
        }
        return jdbcClient.sql("""
                        SELECT DISTINCT account.id, account.status, account.is_test, account.metadata->>'source' AS source
                        FROM customer_accounts account
                        JOIN customer_auth_identities identity ON identity.customer_id = account.id
                        WHERE account.status <> 'DELETED'
                          AND identity.identity_value IN (:values)
                        """)
                .param("values", values)
                .query((rs, rowNum) -> new IdentityOwner(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("is_test"),
                        rs.getString("source")))
                .list();
    }

    private UUID insertFreshAccount(String email, String displayName, String mobile) {
        assertIdentityValueFree(email);
        if (mobile != null) {
            assertIdentityValueFree(mobile);
        }
        UUID customerId = jdbcClient.sql("""
                        INSERT INTO customer_accounts (primary_email, display_name, status, is_test, metadata)
                        VALUES (:email, :displayName, 'ACTIVE', TRUE, CAST(:metadata AS jsonb))
                        RETURNING id
                        """)
                .param("email", email)
                .param("displayName", displayName)
                .param("metadata", "{\"source\":\"" + TEST_USER_SOURCE + "\"}")
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();
        insertVerifiedIdentity(customerId, "EMAIL", email);
        if (mobile != null) {
            insertVerifiedIdentity(customerId, "MOBILE", mobile);
        }
        return customerId;
    }

    private UUID convertJunkAccount(UUID customerId, String email, String displayName, String mobile) {
        jdbcClient.sql("""
                        UPDATE customer_accounts
                        SET status = 'ACTIVE', display_name = :displayName, is_test = TRUE,
                            primary_email = COALESCE(primary_email, :email), updated_at = now()
                        WHERE id = :id
                        """)
                .param("displayName", displayName)
                .param("email", email)
                .param("id", customerId)
                .update();
        jdbcClient.sql("""
                        UPDATE customer_auth_identities
                        SET is_verified = TRUE, last_verified_at = now(), updated_at = now()
                        WHERE customer_id = :id
                        """)
                .param("id", customerId)
                .update();
        ensureIdentity(customerId, "EMAIL", email);
        if (mobile != null) {
            ensureIdentity(customerId, "MOBILE", mobile);
        }
        return customerId;
    }

    private void ensureIdentity(UUID customerId, String identityType, String identityValue) {
        Optional<UUID> owner = jdbcClient.sql("""
                        SELECT customer_id FROM customer_auth_identities WHERE identity_value = :value LIMIT 1
                        """)
                .param("value", identityValue)
                .query((rs, rowNum) -> rs.getObject("customer_id", UUID.class))
                .optional();
        if (owner.isPresent()) {
            if (!owner.get().equals(customerId)) {
                throw new IllegalStateException("identity already in use");
            }
            return;
        }
        insertVerifiedIdentity(customerId, identityType, identityValue);
    }

    private void assertIdentityValueFree(String identityValue) {
        boolean taken = jdbcClient.sql("""
                        SELECT 1 FROM customer_auth_identities WHERE identity_value = :value LIMIT 1
                        """)
                .param("value", identityValue)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (taken) {
            throw new IllegalStateException("identity already in use");
        }
    }

    private void insertVerifiedIdentity(UUID customerId, String identityType, String identityValue) {
        jdbcClient.sql("""
                        INSERT INTO customer_auth_identities (
                            customer_id, identity_type, identity_value, is_verified, last_verified_at, metadata
                        ) VALUES (
                            :customerId, :identityType, :identityValue, TRUE, now(), CAST(:metadata AS jsonb)
                        )
                        """)
                .param("customerId", customerId)
                .param("identityType", identityType)
                .param("identityValue", identityValue)
                .param("metadata", "{\"source\":\"" + TEST_USER_SOURCE + "\"}")
                .update();
    }

    private void mintAndStoreOtp(UUID customerId, String note, String reviewedBy) {
        String otp = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        String otpHash = sha256Hex(otp);
        String creator = reviewedBy == null || reviewedBy.isBlank() ? "unknown" : reviewedBy.trim();
        if (creator.length() > 160) {
            creator = creator.substring(0, 160);
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO storefront_test_accounts (
                                customer_id, otp_hash, pending_otp, note, created_by_admin, is_active
                            ) VALUES (
                                :customerId, :otpHash, :pendingOtp, :note, :createdBy, TRUE
                            )
                            """)
                    .param("customerId", customerId)
                    .param("otpHash", otpHash)
                    .param("pendingOtp", otp)
                    .param("note", note)
                    .param("createdBy", creator)
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("test user already exists", exception);
        }
    }

    private UUID parseEntityCustomerId(String entityKey, Map<String, Object> payload) {
        String payloadCustomerId = text(payload, "customerId");
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey must be the customerId");
        }
        if (payloadCustomerId != null && !payloadCustomerId.equalsIgnoreCase(entityKey.trim())) {
            throw new IllegalArgumentException("payload customerId must equal entityKey");
        }
        return parseUuid(entityKey);
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("customerId must be a valid UUID", exception);
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String str && !str.isBlank() ? str.trim() : null;
    }

    private static String normalizeEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMobile(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        return MOBILE_PATTERN.matcher(digits).matches() ? digits : null;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for test user OTP hashing", exception);
        }
    }

    private record IdentityOwner(UUID id, String status, boolean isTest, String source) {
    }

    private record AccountState(UUID id, String status, boolean isTest) {
    }

    private record PendingOtp(String pendingOtp, Instant otpRevealedAt) {
    }
}

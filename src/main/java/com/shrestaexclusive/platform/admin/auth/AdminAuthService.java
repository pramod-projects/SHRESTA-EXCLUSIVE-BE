package com.shrestaexclusive.platform.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shrestaexclusive.platform.storefront.admin.StorefrontAdminProperties;

@Service
public class AdminAuthService {

    private static final String ONLY_SUPER_ADMIN_EMAIL = "pramod.works.on.projects@gmail.com";
    private static final Set<String> KNOWN_ROLES = Set.of("SUPER_ADMIN", "CHANGE_SUBMITTER", "CHANGE_APPROVER", "CHANGE_MANAGER", "CHANGE_ADMIN");
    private static final Map<String, List<String>> PERMISSIONS_BY_ROLE = Map.of(
            "CHANGE_SUBMITTER", List.of("admin:read", "change_request:submit"),
        "CHANGE_APPROVER", List.of("admin:read", "change_request:read", "change_request:approve", "change_request:reject"),
            "CHANGE_MANAGER", List.of("admin:read", "change_request:submit", "change_request:read", "change_request:approve", "change_request:reject"),
        "CHANGE_ADMIN", List.of("admin:read", "change_request:submit", "change_request:read", "change_request:approve", "change_request:reject"),
            "SUPER_ADMIN", List.of("admin:*")
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final StorefrontAdminProperties adminProperties;
    private final Clock clock;

    @Autowired
    public AdminAuthService(NamedParameterJdbcTemplate jdbcTemplate, StorefrontAdminProperties adminProperties) {
        this(jdbcTemplate, adminProperties, Clock.systemUTC());
    }

    AdminAuthService(NamedParameterJdbcTemplate jdbcTemplate, StorefrontAdminProperties adminProperties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminProperties = adminProperties;
        this.clock = clock;
    }

    @Transactional
    public AdminLoginResponse login(String email, String password) {
        ensureBootstrapAdmin();

        String normalizedEmail = normalizeEmail(email);
        String normalizedPasswordHash = passwordHash(password);

        AdminUserRow row = jdbcTemplate.query("""
                SELECT email, password_hash, role, is_active, created_by_email, created_at, updated_at
                FROM admin_users
                WHERE email = :email
                LIMIT 1
                """, new MapSqlParameterSource("email", normalizedEmail), (rs, rowNum) -> mapRow(rs))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AdminAuthUnauthorizedException());

        if (!row.active() || !secureEquals(row.passwordHash(), normalizedPasswordHash)) {
            throw new AdminAuthUnauthorizedException();
        }

        String normalizedRole = normalizeRole(row.role());
        if ("SUPER_ADMIN".equals(normalizedRole)) {
            if (!normalizedEmail.equals(normalizeEmail(ONLY_SUPER_ADMIN_EMAIL))) {
                throw new AdminAuthUnauthorizedException();
            }
        }
        return new AdminLoginResponse(
                row.email(),
                normalizedRole,
                PERMISSIONS_BY_ROLE.getOrDefault(normalizedRole, List.of())
        );
    }

    @Transactional
    public AdminUserResponse createAdmin(String actorEmail, AdminUserCreateRequest request) {
        ensureBootstrapAdmin();
        String email = normalizeEmail(request.email());
        String creator = normalizeEmail(actorEmail);
        if (!StringUtils.hasText(creator)) {
            creator = "super-admin";
        }
        String role = normalizeRole(request.role());
        if (!KNOWN_ROLES.contains(role)) {
            throw new IllegalArgumentException("Unsupported admin role.");
        }
        if ("SUPER_ADMIN".equals(role)) {
            throw new IllegalArgumentException("SUPER_ADMIN can only be bootstrapped from environment configuration.");
        }

        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int FROM admin_users WHERE email = :email
                """, new MapSqlParameterSource("email", email), Integer.class);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("Admin with this email already exists.");
        }

        Instant now = Instant.now(clock);
        jdbcTemplate.update("""
                INSERT INTO admin_users (email, password_hash, role, is_active, created_by_email, created_at, updated_at)
                VALUES (:email, :passwordHash, :role, true, :createdByEmail, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("passwordHash", passwordHash(request.password()))
                .addValue("role", role)
                .addValue("createdByEmail", creator)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("updatedAt", Timestamp.from(now))
        );

            return new AdminUserResponse(email, role, true, creator, now, now);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listAdmins() {
        return jdbcTemplate.query("""
                SELECT email, role, is_active, created_by_email, created_at, updated_at
                FROM admin_users
                ORDER BY created_at ASC
                """, (rs, rowNum) -> new AdminUserResponse(
                rs.getString("email"),
                normalizeRole(rs.getString("role")),
                rs.getBoolean("is_active"),
                rs.getString("created_by_email"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    @Transactional
    public AdminUserResponse deleteAdmin(String actorEmail, String targetEmail) {
        ensureBootstrapAdmin();

        String normalizedActor = normalizeEmail(actorEmail);
        String normalizedTarget = normalizeEmail(targetEmail);
        if (!StringUtils.hasText(normalizedTarget)) {
            throw new IllegalArgumentException("Admin email is required.");
        }
        if (StringUtils.hasText(normalizedActor) && normalizedActor.equals(normalizedTarget)) {
            throw new IllegalArgumentException("You cannot delete your own admin account.");
        }

        String bootstrapEmail = normalizeEmail(ONLY_SUPER_ADMIN_EMAIL);
        if (StringUtils.hasText(bootstrapEmail) && bootstrapEmail.equals(normalizedTarget)) {
            throw new IllegalArgumentException("Bootstrap SUPER_ADMIN cannot be deleted.");
        }

        AdminUserRow row = jdbcTemplate.query("""
                SELECT email, password_hash, role, is_active, created_by_email, created_at, updated_at
                FROM admin_users
                WHERE email = :email
                LIMIT 1
                """, new MapSqlParameterSource("email", normalizedTarget), (rs, rowNum) -> mapRow(rs))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found."));

        jdbcTemplate.update("""
                DELETE FROM admin_users
                WHERE email = :email
                """, new MapSqlParameterSource("email", normalizedTarget));

        return new AdminUserResponse(
                row.email(),
                normalizeRole(row.role()),
                row.active(),
                row.createdByEmail(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private void ensureBootstrapAdmin() {
        String email = normalizeEmail(ONLY_SUPER_ADMIN_EMAIL);
        if (!StringUtils.hasText(email)) {
            return;
        }

        // Keep a single source of truth: only the canonical bootstrap account may remain SUPER_ADMIN.
        jdbcTemplate.update("""
                UPDATE admin_users
                                SET role = 'CHANGE_ADMIN',
                    updated_at = :updatedAt
                WHERE role = 'SUPER_ADMIN'
                  AND email <> :email
                """, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("updatedAt", Timestamp.from(Instant.now(clock))));

                jdbcTemplate.update("""
                                UPDATE admin_users
                                SET role = 'CHANGE_APPROVER',
                                        updated_at = :updatedAt
                                WHERE role = 'CHANGE_REVIEWER'
                                """, new MapSqlParameterSource()
                                .addValue("updatedAt", Timestamp.from(Instant.now(clock))));

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int FROM admin_users WHERE email = :email
                """, new MapSqlParameterSource("email", email), Integer.class);
        if (count != null && count > 0) {
            return;
        }

        Instant now = Instant.now(clock);
        String role = normalizeRole(adminProperties.getBootstrapRole());
        if (!KNOWN_ROLES.contains(role)) {
            role = "SUPER_ADMIN";
        }

        jdbcTemplate.update("""
                INSERT INTO admin_users (email, password_hash, role, is_active, created_by_email, created_at, updated_at)
                VALUES (:email, :passwordHash, :role, true, :createdByEmail, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("passwordHash", passwordHash(adminProperties.getBootstrapPassword()))
                .addValue("role", role)
                .addValue("createdByEmail", "system-bootstrap")
                .addValue("createdAt", Timestamp.from(now))
                .addValue("updatedAt", Timestamp.from(now))
        );
    }

    private static AdminUserRow mapRow(ResultSet rs) throws SQLException {
        return new AdminUserRow(
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getBoolean("is_active"),
                rs.getString("created_by_email"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if ("CHANGE_REVIEWER".equals(normalized)) {
            return "CHANGE_APPROVER";
        }
        if ("CHANGE_ALL_ACCESS".equals(normalized)) {
            return "CHANGE_ADMIN";
        }
        return normalized;
    }

    private static String passwordHash(String password) {
        String normalized = password == null ? "" : password;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Password hashing unavailable", exception);
        }
    }

    private static boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private record AdminUserRow(
            String email,
            String passwordHash,
            String role,
            boolean active,
            String createdByEmail,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}

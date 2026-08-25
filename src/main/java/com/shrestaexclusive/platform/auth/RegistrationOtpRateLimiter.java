package com.shrestaexclusive.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RegistrationOtpRateLimiter {
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration ROLLING_WINDOW = Duration.ofHours(1);
    private static final int ROLLING_LIMIT = 3;
    private static final DefaultRedisScript<Long> ROLLING_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if count > tonumber(ARGV[1]) then return 0 end
            return count
            """, Long.class);
    private final RedisTemplate<String, String> redis;

    public RegistrationOtpRateLimiter(RedisTemplate<String, String> shrestaKvRedisTemplate) {
        this.redis = shrestaKvRedisTemplate;
    }

    void acquire(String email, String mobile) {
        enforceRollingLimit("email", email);
        enforceRollingLimit("mobile", mobile);
        String cooldownKey = "auth:registration-otp:cooldown:" + sha256(email + "\n" + mobile);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN))) {
            throw new CustomerRegistrationVerificationException("Please wait before requesting another verification code.");
        }
    }

    void acquireIdentity(String identityType, String identity) {
        try {
            enforceRollingLimit(identityType.toLowerCase(java.util.Locale.ROOT), identity);
        } catch (CustomerRegistrationVerificationException exception) {
            throw new CustomerOtpRequestException(exception.getMessage());
        }
        String cooldownKey = "auth:customer-otp:cooldown:" + sha256(identityType + "\n" + identity);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN))) {
            throw new CustomerOtpRequestException("Please wait before requesting another verification code.");
        }
    }

    private void enforceRollingLimit(String identityType, String identity) {
        String key = "auth:registration-otp:limit:" + identityType + ":" + sha256(identity);
        Long result = redis.execute(ROLLING_LIMIT_SCRIPT, List.of(key),
                String.valueOf(ROLLING_LIMIT), String.valueOf(ROLLING_WINDOW.toSeconds()));
        if (result == null || result == 0) {
                throw new CustomerRegistrationVerificationException(
                    "OTP request limit reached. You can request one initial OTP and two resends per hour. Please try again after one hour.");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
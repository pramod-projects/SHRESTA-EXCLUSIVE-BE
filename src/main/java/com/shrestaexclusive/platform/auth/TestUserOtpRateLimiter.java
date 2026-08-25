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
public class TestUserOtpRateLimiter {
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final DefaultRedisScript<Long> FAILURE_COUNT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final RedisTemplate<String, String> redis;

    public TestUserOtpRateLimiter(RedisTemplate<String, String> shrestaKvRedisTemplate) {
        this.redis = shrestaKvRedisTemplate;
    }

    void acquire(String identity) {
        String lockKey = "auth:test-user-otp:lock:" + sha256(identity);
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
            throw new CustomerOtpRequestException("Too many failed attempts. Please try again after some time.");
        }
        String cooldownKey = "auth:test-user-otp:cooldown:" + sha256(identity);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN))) {
            throw new CustomerOtpRequestException("Please wait before requesting another verification code.");
        }
    }

    void recordFailure(String identity) {
        String failuresKey = "auth:test-user-otp:failures:" + sha256(identity);
        Long count = redis.execute(FAILURE_COUNT_SCRIPT, List.of(failuresKey), String.valueOf(LOCKOUT.toSeconds()));
        if (count != null && count > MAX_FAILURES) {
            redis.opsForValue().set("auth:test-user-otp:lock:" + sha256(identity), "1", LOCKOUT);
            redis.delete(failuresKey);
        }
    }

    void recordSuccess(String identity) {
        redis.delete("auth:test-user-otp:failures:" + sha256(identity));
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

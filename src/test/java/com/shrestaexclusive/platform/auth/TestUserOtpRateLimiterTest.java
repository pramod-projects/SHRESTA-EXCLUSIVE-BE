package com.shrestaexclusive.platform.auth;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TestUserOtpRateLimiterTest {
    @Test
    void enforcesCooldownAndRejectsSecondAttemptWithinSixtySeconds() {
        RedisTemplate<String, String> redis = redis();
        ValueOperations<String, String> values = values(redis);
        when(values.setIfAbsent(startsWith("auth:test-user-otp:cooldown:"), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(true, false);
        TestUserOtpRateLimiter limiter = new TestUserOtpRateLimiter(redis);

        limiter.acquire("customer@example.com");
        assertThatThrownBy(() -> limiter.acquire("customer@example.com"))
                .isInstanceOf(CustomerOtpRequestException.class)
                .hasMessage("Please wait before requesting another verification code.");
    }

    @Test
    void rejectsLockedIdentityBeforeCooldown() {
        RedisTemplate<String, String> redis = redis();
        when(redis.hasKey(startsWith("auth:test-user-otp:lock:"))).thenReturn(true);
        TestUserOtpRateLimiter limiter = new TestUserOtpRateLimiter(redis);

        assertThatThrownBy(() -> limiter.acquire("customer@example.com"))
                .isInstanceOf(CustomerOtpRequestException.class)
                .hasMessage("Too many failed attempts. Please try again after some time.");
        verify(redis, never()).opsForValue();
    }

    @Test
    void sixthConsecutiveFailureLocksIdentityForFifteenMinutes() {
        RedisTemplate<String, String> redis = redis();
        ValueOperations<String, String> values = values(redis);
        when(redis.execute(any(), anyList(), anyString())).thenReturn(6L);
        TestUserOtpRateLimiter limiter = new TestUserOtpRateLimiter(redis);

        limiter.recordFailure("customer@example.com");

        verify(values).set(startsWith("auth:test-user-otp:lock:"), eq("1"), eq(Duration.ofMinutes(15)));
        verify(redis).delete(startsWith("auth:test-user-otp:failures:"));
    }

    @Test
    void failuresUpToFiveDoNotLock() {
        RedisTemplate<String, String> redis = redis();
        ValueOperations<String, String> values = values(redis);
        when(redis.execute(any(), anyList(), anyString())).thenReturn(5L);
        TestUserOtpRateLimiter limiter = new TestUserOtpRateLimiter(redis);

        limiter.recordFailure("customer@example.com");

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void successClearsFailureCounter() {
        RedisTemplate<String, String> redis = redis();
        TestUserOtpRateLimiter limiter = new TestUserOtpRateLimiter(redis);

        limiter.recordSuccess("customer@example.com");

        verify(redis).delete(startsWith("auth:test-user-otp:failures:"));
    }

    @SuppressWarnings("unchecked")
    private static RedisTemplate<String, String> redis() {
        return mock(RedisTemplate.class);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> values(RedisTemplate<String, String> redis) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return values;
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.matches(java.util.regex.Pattern.quote(prefix) + ".*");
    }
}

package com.shrestaexclusive.platform.auth;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RegistrationOtpRateLimiterTest {
    @Test
    void storesHashedIdentityCooldownAndRejectsDuplicateRequest() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(), anyList(), anyString(), anyString())).thenReturn(1L);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(true, false);
        RegistrationOtpRateLimiter limiter = new RegistrationOtpRateLimiter(redis);

        limiter.acquire("customer@example.com", "+919999999999");
        assertThatThrownBy(() -> limiter.acquire("customer@example.com", "+919999999999"))
                .isInstanceOf(CustomerRegistrationVerificationException.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).setIfAbsent(keys.capture(), eq("1"), eq(Duration.ofSeconds(60)));
        List<String> capturedKeys = keys.getAllValues();
        assertThat(capturedKeys).containsOnly(capturedKeys.getFirst());
        assertThat(capturedKeys.getFirst()).startsWith("auth:registration-otp:cooldown:")
                .doesNotContain("customer@example.com", "9999999999");
    }

        @Test
        void rejectsWhenEitherRollingIdentityLimitIsExceeded() {
                @SuppressWarnings("unchecked")
                RedisTemplate<String, String> redis = mock(RedisTemplate.class);
                when(redis.execute(any(), anyList(), anyString(), anyString()))
                                .thenReturn(1L, 0L);
                RegistrationOtpRateLimiter limiter = new RegistrationOtpRateLimiter(redis);

                assertThatThrownBy(() -> limiter.acquire("customer@example.com", "+919999999999"))
                                .isInstanceOf(CustomerRegistrationVerificationException.class)
                                .hasMessage("OTP request limit reached. You can request one initial OTP and two resends per hour. Please try again after one hour.");
                verify(redis, times(2)).execute(any(), anyList(), eq("3"), eq("3600"));
        }
}

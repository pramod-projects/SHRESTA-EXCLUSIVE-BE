package com.shrestaexclusive.platform.mutation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.kv.KvCacheProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;

class RedisIdempotentMutationCoordinator implements IdempotentMutationCoordinator {

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final KvCacheProperties kvProperties;
    private final MutationSafetyProperties safetyProperties;

    RedisIdempotentMutationCoordinator(
            RedisTemplate<String, String> redis,
            ObjectMapper objectMapper,
            KvCacheProperties kvProperties,
            MutationSafetyProperties safetyProperties
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.kvProperties = kvProperties;
        this.safetyProperties = safetyProperties;
    }

    @Override
    public <T> T run(
            String scope,
            String submittedIdempotencyKey,
            String requestFingerprint,
            String lockKey,
            TypeReference<T> responseType,
            Supplier<T> mutation
    ) {
        String idempotencyKey = normalizeIdempotencyKey(submittedIdempotencyKey);
        if (!safetyProperties.getIdempotency().isEnabled()) {
            return runWithLock(lockKey, mutation);
        }

        try {
            String recordKey = idempotencyRecordKey(scope, idempotencyKey);
            IdempotencyRecord existing = readRecord(recordKey);
            if (existing != null) {
                return replay(existing, requestFingerprint, responseType);
            }

            return runWithLock(lockKey, () -> {
                IdempotencyRecord doubleChecked = readRecord(recordKey);
                if (doubleChecked != null) {
                    return replay(doubleChecked, requestFingerprint, responseType);
                }

                T result = mutation.get();
                writeRecord(recordKey, requestFingerprint, result);
                return result;
            });
        } catch (RedisConnectionFailureException | RedisSystemException exception) {
            throw new MutationSafetyUnavailableException(exception);
        }
    }

    private <T> T runWithLock(String lockKey, Supplier<T> mutation) {
        if (!safetyProperties.getLocking().isEnabled()) {
            return mutation.get();
        }

        String redisLockKey = lockKey(lockKey);
        String lockValue = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(redisLockKey, lockValue, safetyProperties.getLocking().getTtl());
            if (!Boolean.TRUE.equals(acquired)) {
                throw new MutationLockConflictException(lockKey);
            }
            try {
                return mutation.get();
            } finally {
                redis.execute(RELEASE_LOCK_SCRIPT, List.of(redisLockKey), lockValue);
            }
        } catch (RedisConnectionFailureException | RedisSystemException exception) {
            throw new MutationSafetyUnavailableException(exception);
        }
    }

    private String normalizeIdempotencyKey(String submittedIdempotencyKey) {
        if (StringUtils.hasText(submittedIdempotencyKey)) {
            return submittedIdempotencyKey.trim();
        }
        if (safetyProperties.getIdempotency().isRequireKey()) {
            throw new IdempotencyKeyRequiredException();
        }
        return UUID.randomUUID().toString();
    }

    private <T> T replay(IdempotencyRecord record, String requestFingerprint, TypeReference<T> responseType) {
        if (!record.requestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyConflictException();
        }
        try {
            return objectMapper.readValue(record.responseJson(), responseType);
        } catch (JsonProcessingException exception) {
            throw new MutationSafetyUnavailableException(exception);
        }
    }

    private IdempotencyRecord readRecord(String recordKey) {
        String json = redis.opsForValue().get(recordKey);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IdempotencyRecord.class);
        } catch (JsonProcessingException exception) {
            throw new MutationSafetyUnavailableException(exception);
        }
    }

    private void writeRecord(String recordKey, String requestFingerprint, Object response) {
        try {
            IdempotencyRecord record = new IdempotencyRecord(
                    requestFingerprint,
                    objectMapper.writeValueAsString(response),
                    Instant.now()
            );
            redis.opsForValue().set(
                    recordKey,
                    objectMapper.writeValueAsString(record),
                    safetyProperties.getIdempotency().getTtl()
            );
        } catch (JsonProcessingException exception) {
            throw new MutationSafetyUnavailableException(exception);
        }
    }

    private String idempotencyRecordKey(String scope, String submittedIdempotencyKey) {
        return kvProperties.getKeyPrefix()
                + ":idempotency:"
                + hash(scope + ":" + submittedIdempotencyKey);
    }

    private String lockKey(String lockKey) {
        return kvProperties.getKeyPrefix() + ":lock:" + hash(lockKey);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for mutation safety keys", exception);
        }
    }

    private record IdempotencyRecord(
            String requestFingerprint,
            String responseJson,
            Instant completedAt
    ) {
    }
}

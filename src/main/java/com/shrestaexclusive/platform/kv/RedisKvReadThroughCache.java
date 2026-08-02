package com.shrestaexclusive.platform.kv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;

class RedisKvReadThroughCache implements KvReadThroughCache {

    private static final Logger log = LoggerFactory.getLogger(RedisKvReadThroughCache.class);

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final KvCacheProperties properties;

    RedisKvReadThroughCache(
            RedisTemplate<String, String> redis,
            ObjectMapper objectMapper,
            KvCacheProperties properties
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public <T> T getOrLoad(String cacheName, String cacheKey, List<String> tableNames, TypeReference<T> type, Supplier<T> loader) {
        if (!kvEnabled(tableNames)) {
            return loader.get();
        }

        try {
            Map<String, String> versions = versions(tableNames);
            String valueKey = valueKey(cacheName, cacheKey, versions);
            String json = redis.opsForValue().get(valueKey);
            if (json != null) {
                return objectMapper.readValue(json, type);
            }

            T value = loader.get();
            put(cacheName, cacheKey, tableNames, versions, value);
            return value;
        } catch (JsonProcessingException | RedisConnectionFailureException | RedisSystemException exception) {
            log.warn("KV cache read failed for cache={} key={}; falling back to DB", cacheName, cacheKey, exception);
            return loader.get();
        }
    }

    @Override
    public void putFresh(String cacheName, String cacheKey, List<String> tableNames, Object value) {
        if (!kvEnabled(tableNames)) {
            return;
        }

        try {
            put(cacheName, cacheKey, tableNames, versions(tableNames), value);
        } catch (JsonProcessingException | RedisConnectionFailureException | RedisSystemException exception) {
            log.warn("KV cache write failed for cache={} key={}", cacheName, cacheKey, exception);
        }
    }

    @Override
    public void invalidateTables(List<String> tableNames) {
        if (!properties.isEnabled()) {
            return;
        }

        for (String tableName : tableNames) {
            if (!tableEnabled(tableName)) {
                continue;
            }
            try {
                redis.opsForValue().increment(versionKey(tableName));
            } catch (RedisConnectionFailureException | RedisSystemException exception) {
                log.warn("KV cache invalidation failed for table={}", tableName, exception);
            }
        }
    }

    private void put(String cacheName, String cacheKey, List<String> tableNames, Map<String, String> versions, Object value) throws JsonProcessingException {
        redis.opsForValue().set(
                valueKey(cacheName, cacheKey, versions),
                objectMapper.writeValueAsString(value),
                ttl(tableNames)
        );
    }

    private Map<String, String> versions(List<String> tableNames) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            String version = redis.opsForValue().get(versionKey(tableName));
            if (version == null) {
                version = "1";
                redis.opsForValue().setIfAbsent(versionKey(tableName), version);
            }
            versions.put(tableName, version);
        }
        return versions;
    }

    private boolean kvEnabled(List<String> tableNames) {
        if (!properties.isEnabled() || tableNames.isEmpty()) {
            return false;
        }

        return tableNames.stream().allMatch(this::tableEnabled);
    }

    private boolean tableEnabled(String tableName) {
        KvTableProperties table = properties.getTables().get(tableName);
        return table == null || table.isEnabled();
    }

    private Duration ttl(List<String> tableNames) {
        return tableNames.stream()
                .map(tableName -> properties.getTables().get(tableName))
                .filter(table -> table != null && table.getTtl() != null)
                .map(KvTableProperties::getTtl)
                .min(Duration::compareTo)
                .orElse(Duration.ofMinutes(5));
    }

    private String valueKey(String cacheName, String cacheKey, Map<String, String> versions) {
        StringBuilder builder = new StringBuilder(properties.getKeyPrefix())
                .append(":kv:")
                .append(cacheName)
                .append(":")
                .append(cacheKey);
        versions.forEach((tableName, version) -> builder
                .append(":")
                .append(tableName)
                .append("@")
                .append(version));
        return builder.toString();
    }

    private String versionKey(String tableName) {
        return properties.getKeyPrefix() + ":kv-version:" + tableName;
    }
}

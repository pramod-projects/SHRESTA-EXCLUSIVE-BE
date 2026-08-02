package com.shrestaexclusive.platform.kv;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.function.Supplier;

public interface KvReadThroughCache {

    <T> T getOrLoad(String cacheName, String cacheKey, List<String> tableNames, TypeReference<T> type, Supplier<T> loader);

    void putFresh(String cacheName, String cacheKey, List<String> tableNames, Object value);

    void invalidateTables(List<String> tableNames);
}
